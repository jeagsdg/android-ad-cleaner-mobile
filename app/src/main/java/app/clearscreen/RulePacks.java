package app.clearscreen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.AtomicFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Process-wide snapshot shared by the rule directory and the accessibility service. */
final class RulePacks {
    enum Refresh { AVAILABLE, UPDATED, CURRENT, ROLLED_BACK, UNAVAILABLE, FAILED }

    interface Feed {
        RulePack fetch(Decoder decoder) throws IOException;
    }

    interface Decoder {
        RulePack verify(byte[] envelope) throws IOException;
    }

    static final class Coverage {
        final String name;
        final int splash, popup;

        Coverage(String name, int splash, int popup) {
            this.name = name;
            this.splash = splash;
            this.popup = popup;
        }
    }

    static final class CatalogRule {
        final String id, packageName, name, title, sourceUrl, testedAppVersion, matchCondition;
        final boolean splash, enabled;
        final long verifiedVersion;

        CatalogRule(RulePack.Entry entry, boolean enabled, long version) {
            id = entry.id;
            splash = entry.splash;
            packageName = entry.rule.optString("package");
            name = entry.rule.optString("name", packageName);
            title = entry.rule.optString("group", splash ? "开屏广告" : "应用内广告");
            matchCondition = entry.rule.optString("selector", "");
            sourceUrl = entry.sourceUrl;
            JSONObject reference = entry.rule.optJSONObject("reference");
            testedAppVersion = reference == null ? "" : reference.optString("versionName");
            verifiedVersion = version;
            this.enabled = enabled;
        }
    }

    static final class State {
        final long version, previousVersion, newestVersion;
        final boolean canRollback;
        final List<CatalogRule> rules;
        final Map<String, Coverage> coverage;
        final SplashRules splashRules;
        final PopupAdRules popupRules;

        State(RulePack active, RulePack previous, Snapshot saved) {
            version = active.version;
            previousVersion = previous == null ? 0 : previous.version;
            newestVersion = saved.newest;
            canRollback = version > 0;
            List<CatalogRule> entries = new ArrayList<>();
            Map<String, Coverage> counts = new HashMap<>();
            JSONArray splash = new JSONArray();
            JSONArray popup = new JSONArray();
            for (RulePack.Entry entry : active.entries) {
                boolean enabled = !saved.disabled.contains(entry.id);
                CatalogRule row = new CatalogRule(entry, enabled, version);
                entries.add(row);
                Coverage count = counts.getOrDefault(row.packageName, new Coverage(row.name, 0, 0));
                counts.put(row.packageName, new Coverage(count.name, count.splash + (row.splash ? 1 : 0),
                        count.popup + (row.splash ? 0 : 1)));
                if (enabled) (row.splash ? splash : popup).put(entry.rule);
            }
            rules = Collections.unmodifiableList(entries);
            coverage = Collections.unmodifiableMap(counts);
            try {
                splashRules = new SplashRules(new JSONObject().put("rules", splash).put("sdkRules", active.sdkRules));
            } catch (JSONException e) {
                throw new IllegalStateException("无法加载开屏规则", e);
            }
            popupRules = new PopupAdRules(popup);
        }
    }

    static final class Preview {
        final RulePack candidate;
        final String description;

        Preview(RulePack current,RulePack candidate) throws JSONException {
            this.candidate=candidate;
            Map<String,RulePack.Entry> before=new HashMap<>();
            for(RulePack.Entry row:current.entries)before.put(row.id,row);
            StringBuilder details=new StringBuilder();
            int added=0, changed=0, removed=0;
            boolean reordered=false;
            List<String> sharedBefore=new ArrayList<>(), sharedAfter=new ArrayList<>();
            Set<String> afterIds=new HashSet<>();
            for(RulePack.Entry row:candidate.entries){
                afterIds.add(row.id);
                RulePack.Entry old=before.get(row.id);
                if(old==null){added++;append(details,"新增",row);}
                else{
                    sharedAfter.add(row.id);
                    if(!RulePack.sameJson(old.rule,row.rule)){
                        changed++;
                        append(details,"修改",row);
                        appendChanges(details,old.rule,row.rule);
                    }
                }
            }
            for(RulePack.Entry row:current.entries){
                if(!afterIds.contains(row.id)){removed++;append(details,"移除",row);}
                else sharedBefore.add(row.id);
            }
            reordered=!sharedBefore.equals(sharedAfter);
            description="版本 "+current.version+" → "+candidate.version+"\n新增 "+added+" · 修改 "+changed+" · 移除 "+removed
                    +(reordered?"\n原有规则的匹配顺序有变更。":"")+details;
        }

        private static void appendChanges(StringBuilder out,JSONObject before,JSONObject after)throws JSONException{
            Set<String> fields=new java.util.TreeSet<>();
            before.keys().forEachRemaining(fields::add);
            after.keys().forEachRemaining(fields::add);
            for(String field:fields){
                Object old=before.opt(field), next=after.opt(field);
                if(old!=null && next!=null && RulePack.sameJson(old,next))continue;
                out.append("\n  ").append(field).append(" · ").append(displayValue(old))
                        .append(" → ").append(displayValue(next));
            }
        }

        private static String displayValue(Object value){
            if(value==null)return "（未设置）";
            return value.toString();
        }

        private static void append(StringBuilder out,String action,RulePack.Entry row){
            out.append('\n').append(action).append(" · ").append(row.rule.optString("package"))
                    .append(" · ").append(row.rule.optString("group",row.splash?"开屏广告":"应用内广告"));
        }
    }

    private static final int FILE_FORMAT = 0x43525031; // CRP1
    private static final int MAX_DISABLED = 4096;
    private static Context appContext;
    private static RulePacks instance;
    private final RulePack bundled;
    private final AtomicFile storage;
    private final Feed feed;
    private final Decoder decoder;
    private volatile State state;
    private Snapshot saved;
    private RulePack active, previous;
    private boolean refreshing;
    private volatile Preview pending;
    private long rollbackGeneration;

    static synchronized RulePacks get(Context context) {
        Context app = context.getApplicationContext();
        if (app != appContext || instance == null) {
            RulePack bundled = RulePack.bundled(new Store(app));
            int client = clientVersion(app);
            instance = new RulePacks(new File(app.getNoBackupFilesDir(), "reviewed-rules.bin"), bundled,
                    supported() ? RulePackTransport::fetch : null,
                    bytes -> verify(bytes, bundled, client, RulePack.CERTIFICATE_SHA256));
            appContext = app;
        }
        return instance;
    }

    static boolean supported() {
        return RulePackTransport.AVAILABLE;
    }

    @SuppressWarnings("deprecation") // versionCode remains an int in the project’s release metadata.
    private static int clientVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    RulePacks(File file, RulePack bundled, Feed feed, Decoder decoder) {
        this.bundled = bundled;
        this.feed = feed;
        this.decoder = decoder;
        storage = new AtomicFile(file);
        try {
            saved = read();
        } catch (IOException e) {
            saved = new Snapshot(0, null, null, Collections.emptySet());
        }
        if (feed != null) {
            active = recover(saved.current);
            previous = recover(saved.previous);
            if (active == null) {
                active = previous;
                previous = null;
            }
        }
        state = new State(active == null ? bundled : active, previous, saved);
    }

    State state() {
        return state;
    }

    Preview preview(){return pending;}

    Refresh refresh() {
        if (feed == null) return Refresh.UNAVAILABLE;
        long generation;
        synchronized (this) {
            if (refreshing) return Refresh.UNAVAILABLE;
            refreshing = true;
            generation = rollbackGeneration;
        }
        try {
            RulePack candidate = feed.fetch(decoder);
            synchronized (this) {
                if (candidate.version == state.version) return Refresh.CURRENT;
                if (generation != rollbackGeneration || candidate.version <= saved.newest) {
                    return state.version < saved.newest ? Refresh.ROLLED_BACK : Refresh.CURRENT;
                }
                pending=new Preview(active==null?bundled:active,candidate);
                return Refresh.AVAILABLE;
            }
        } catch (IOException|JSONException e) {
            return Refresh.FAILED;
        } finally {
            synchronized (this) {
                refreshing = false;
            }
        }
    }

    synchronized Refresh apply(Preview preview){
        if(preview==null || preview!=pending || preview.candidate.version<=saved.newest)return Refresh.UNAVAILABLE;
        RulePack next=preview.candidate;
        if(!install(next,active,new Snapshot(next.version,next.envelope,envelope(active),saved.disabled)))return Refresh.FAILED;
        pending=null;
        return Refresh.UPDATED;
    }

    synchronized boolean rollback() {
        if (!state.canRollback) return false;
        if (!install(previous, null, new Snapshot(saved.newest, envelope(previous), null, saved.disabled))) return false;
        pending=null;
        rollbackGeneration++;
        return true;
    }

    synchronized boolean setRuleEnabled(String id, boolean enabled) {
        if (state.rules.stream().noneMatch(row -> row.id.equals(id))) return false;
        Set<String> disabled = new HashSet<>(saved.disabled);
        boolean changed = enabled ? disabled.remove(id) : disabled.add(id);
        if (!changed) return true;
        if (disabled.size() > MAX_DISABLED) return false;
        return install(active, previous, new Snapshot(saved.newest, saved.current, saved.previous, disabled));
    }

    private boolean install(RulePack next, RulePack prior, Snapshot write) {
        State updated = new State(next == null ? bundled : next, prior, write);
        if (!persist(write)) return false;
        active = next;
        previous = prior;
        saved = write;
        state = updated;
        return true;
    }

    private RulePack recover(byte[] envelope) {
        if (envelope == null) return null;
        try {
            RulePack pack = decoder.verify(envelope);
            return pack.version <= saved.newest ? pack : null;
        } catch (IOException e) {
            return null;
        }
    }

    static RulePack verify(byte[] bytes, RulePack bundled, int client, String pin) throws IOException {
        try {
            return RulePack.verify(bytes, bundled, client, pin);
        } catch (GeneralSecurityException | JSONException e) {
            throw new IOException("无法验证规则包", e);
        }
    }

    private static byte[] envelope(RulePack pack) {
        return pack == null ? null : pack.envelope;
    }

    private boolean persist(Snapshot snapshot) {
        FileOutputStream output = null;
        try {
            output = storage.startWrite();
            DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output));
            data.writeInt(FILE_FORMAT);
            data.writeLong(snapshot.newest);
            data.writeInt(snapshot.disabled.size());
            for (String id : snapshot.disabled) data.writeUTF(id);
            writePack(data, snapshot.current);
            writePack(data, snapshot.previous);
            data.flush();
            storage.finishWrite(output);
            output = null;
            // AtomicFile logs failures during a final rename. Confirm the commit before exposing it.
            Snapshot confirmed = read();
            return confirmed.newest == snapshot.newest && confirmed.disabled.equals(snapshot.disabled)
                    && Arrays.equals(confirmed.current, snapshot.current) && Arrays.equals(confirmed.previous, snapshot.previous);
        } catch (IOException e) {
            return false;
        } finally {
            if (output != null) storage.failWrite(output);
        }
    }

    private Snapshot read() throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(storage.openRead()))) {
            if (input.readInt() != FILE_FORMAT) throw new IOException("规则存储格式无效");
            long newest = input.readLong();
            int count = input.readInt();
            if (newest < 0 || count < 0 || count > MAX_DISABLED) throw new IOException("规则存储无效");
            Set<String> disabled = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String id = input.readUTF();
                if (id.isEmpty() || id.length() > 160) throw new IOException("规则编号无效");
                disabled.add(id);
            }
            byte[] current = readPack(input);
            byte[] previous = readPack(input);
            if (input.read() != -1) throw new IOException("规则存储无效");
            return new Snapshot(newest, current, previous, disabled);
        }
    }

    private static void writePack(DataOutputStream output, byte[] pack) throws IOException {
        output.writeInt(pack == null ? 0 : pack.length);
        if (pack != null) output.write(pack);
    }

    private static byte[] readPack(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > RulePack.MAX_ENVELOPE_BYTES) throw new IOException("保存的规则包大小无效");
        if (size == 0) return null;
        byte[] pack = new byte[size];
        input.readFully(pack);
        return pack;
    }

    private static final class Snapshot {
        final long newest;
        final byte[] current, previous;
        final Set<String> disabled;

        Snapshot(long newest, byte[] current, byte[] previous, Set<String> disabled) {
            this.newest = newest;
            this.current = current;
            this.previous = previous;
            this.disabled = disabled;
        }
    }
}
