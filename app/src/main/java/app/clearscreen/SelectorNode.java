package app.clearscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Objects;
import java.util.function.Predicate;
import org.json.*;

abstract class SelectorNode {
    abstract String getPackageName();
    abstract String getClassName();
    abstract String getText();
    abstract String getContentDescription();
    abstract String getViewIdResourceName();
    boolean known(String field){return true;}
    abstract boolean isClickable();
    abstract boolean isVisibleToUser();
    abstract int getChildCount();
    abstract SelectorNode getChild(int index);
    abstract SelectorNode getParent();

    static SelectorNode live(AccessibilityNodeInfo node){return node==null?null:new Live(node);}
    private static final class Live extends SelectorNode {
        final AccessibilityNodeInfo n;
        Live(AccessibilityNodeInfo n){this.n=n;}
        private String string(CharSequence v){return v==null?null:v.toString();}
        String getPackageName(){return string(n.getPackageName());}
        String getClassName(){return string(n.getClassName());}
        String getText(){return string(n.getText());}
        String getContentDescription(){return string(n.getContentDescription());}
        String getViewIdResourceName(){return n.getViewIdResourceName();}
        boolean isClickable(){return n.isClickable();}
        boolean isVisibleToUser(){return n.isVisibleToUser();}
        int getChildCount(){return n.getChildCount();}
        SelectorNode getChild(int i){return live(n.getChild(i));}
        SelectorNode getParent(){return live(n.getParent());}
        @Override public boolean equals(Object o){return o instanceof Live && n.equals(((Live)o).n);}
        @Override public int hashCode(){return n.hashCode();}
    }
    private static final int MAX_DISTANCE=12, MAX_VISITS=96;
    static boolean matches(String pkg,SelectorNode node,JSONObject selector,boolean exactPackage){
        if (selector == null) return false;
        JSONArray path = selector.optJSONArray("path");
        JSONArray relations = selector.optJSONArray("relations");
        int target = selector.optInt("target", -1);
        if (path == null || relations == null || relations.length() + 1 != path.length()
                || target < 0 || target >= path.length()
                || !nodeMatches(pkg, node, path.optJSONObject(target), exactPackage)) return false;
        PathCheck check = new PathCheck(pkg, path, relations, exactPackage);
        return check.check(node, target, -1) && check.check(node, target, 1);
    }

    private static final class PathCheck {
        private final String pkg;
        private final JSONArray path;
        private final JSONArray relations;
        private final boolean exactPackage;
        private int remaining = MAX_VISITS;

        PathCheck(String pkg, JSONArray path, JSONArray relations, boolean exactPackage) {
            this.pkg = pkg;
            this.path = path;
            this.relations = relations;
            this.exactPackage = exactPackage;
        }

        // Invert the relations to the right of an explicitly marked target.
        boolean check(SelectorNode node, int from, int direction) {
            int next = from + direction;
            if (next < 0 || next >= path.length()) return true;
            if (remaining <= 0) return false;
            JSONArray relation = relations.optJSONArray(Math.min(from, next));
            if (relation == null) return false;
            JSONArray slots = relation.optJSONArray(1);
            if (slots == null) return false;
            switch (relation.optString(0)) {
                case ">":
                    return direction < 0 ? ancestors(node, slots, next, direction)
                            : descendants(node, slots, next, direction, 1);
                case "<<":
                    if (slots.length() != 0) {
                        return direction > 0 ? indexedAncestors(node, slots, next, direction)
                                : new IndexedDescendants(slots, child -> nodeMatches(pkg, child,
                                        path.optJSONObject(next), exactPackage) && check(child, next, direction)).find(node);
                    }
                    return direction > 0 ? ancestors(node, slots, next, direction)
                            : descendants(node, slots, next, direction, 1);
                case "<":
                    if (direction < 0) return children(node, slots, next, direction);
                    SelectorNode parent = node.getParent();
                    return parent != null && (slots.length() == 0 || accepts(slots, childIndex(parent, node) + 1))
                            && consider(parent, next, direction);
                case "+":
                    return siblings(node, direction, slots, next, direction);
                case "-":
                    return siblings(node, -direction, slots, next, direction);
                default:
                    return false;
            }
        }

        private boolean consider(SelectorNode node, int at, int direction) {
            return remaining-- > 0 && nodeMatches(pkg, node, path.optJSONObject(at), exactPackage)
                    && check(node, at, direction);
        }

        private boolean ancestors(SelectorNode node, JSONArray slots, int next, int direction) {
            for (int distance = 1; distance <= lastSlot(slots) && remaining > 0; distance++) {
                node = node.getParent();
                if (node == null) return false;
                if (accepts(slots, distance) && consider(node, next, direction)) return true;
            }
            return false;
        }

        private boolean indexedAncestors(SelectorNode node, JSONArray slots, int next, int direction) {
            SelectorNode parent = node;
            for (int depth = 1; depth <= lastSlot(slots) && remaining > 0; depth++) {
                parent = parent.getParent();
                if (parent == null) return false;
                remaining--;
                if (nodeMatches(pkg, parent, path.optJSONObject(next), exactPackage)
                        && new IndexedDescendants(slots, node::equals).find(parent)
                        && check(parent, next, direction)) return true;
            }
            return false;
        }

        // Indexed << uses the descendant's one-based preorder position.
        private final class IndexedDescendants {
            private final JSONArray slots;
            private final int limit;
            private final Predicate<SelectorNode> match;
            private int position;

            IndexedDescendants(JSONArray slots, Predicate<SelectorNode> match) {
                this.slots = slots;
                this.limit = lastSlot(slots);
                this.match = match;
            }

            boolean find(SelectorNode parent) {
                for (int i = 0; i < parent.getChildCount() && position < limit && remaining > 0; i++) {
                    remaining--;
                    SelectorNode child = parent.getChild(i);
                    if (child == null) {
                        // An unreadable child makes later positions indeterminate.
                        position = limit;
                        return false;
                    }
                    if (accepts(slots, ++position) && match.test(child)) return true;
                    if (find(child)) return true;
                }
                return false;
            }
        }

        private boolean descendants(SelectorNode node, JSONArray slots, int next, int direction, int depth) {
            if (depth > lastSlot(slots)) return false;
            for (int i = 0; i < node.getChildCount() && remaining > 0; i++) {
                SelectorNode child = node.getChild(i);
                if (accepts(slots, depth)) {
                    if (consider(child, next, direction)) return true;
                } else {
                    remaining--;
                }
                if (child != null && descendants(child, slots, next, direction, depth + 1)) return true;
            }
            return false;
        }

        private boolean children(SelectorNode node, JSONArray slots, int next, int direction) {
            for (int i = 0; i < node.getChildCount() && i < MAX_VISITS && remaining > 0; i++) {
                if (accepts(slots, i + 1) && consider(node.getChild(i), next, direction)) return true;
            }
            return false;
        }

        private boolean siblings(SelectorNode node, int step, JSONArray slots, int next, int direction) {
            SelectorNode parent = node.getParent();
            if (parent == null) return false;
            int index = childIndex(parent, node);
            if (index < 0) return false;
            for (int offset = 1; offset <= lastSlot(slots) && remaining > 0; offset++) {
                int sibling = index + step * offset;
                if (sibling < 0 || sibling >= parent.getChildCount()) return false;
                if (accepts(slots, offset) && consider(parent.getChild(sibling), next, direction)) return true;
            }
            return false;
        }
    }

    private static int childIndex(SelectorNode parent, SelectorNode child) {
        for (int i = 0; i < parent.getChildCount() && i < MAX_VISITS; i++) {
            if (child.equals(parent.getChild(i))) return i;
        }
        return -1;
    }

    private static int lastSlot(JSONArray slots) {
        return slots.length() == 0 ? MAX_DISTANCE : slots.optInt(slots.length() - 1);
    }

    private static boolean accepts(JSONArray slots, int position) {
        if (position < 1) return false;
        if (slots.length() == 0) return true;
        for (int i = 0; i < slots.length(); i++) {
            if (slots.optInt(i) == position) return true;
        }
        return false;
    }

    private static boolean nodeMatches(String pkg, SelectorNode node, JSONObject predicate, boolean exactPackage) {
        if (node == null || predicate == null || !node.isVisibleToUser()
                || (exactPackage && !pkg.equals(String.valueOf(node.getPackageName())))) return false;
        String cls = predicate.optString("class");
        String actual = String.valueOf(node.getClassName());
        if (!cls.equals("*") && !actual.equals(cls) && !actual.endsWith("." + cls)) return false;
        JSONArray tests = predicate.optJSONArray("tests");
        if (tests == null) return false;
        for (int i = 0; i < tests.length(); i++) {
            JSONArray alternatives = tests.optJSONArray(i);
            if (alternatives == null || !anyAlternative(pkg, node, alternatives)) return false;
        }
        return true;
    }

    private static boolean anyAlternative(String pkg, SelectorNode node, JSONArray alternatives) {
        for (int i = 0; i < alternatives.length(); i++) {
            JSONArray choice = alternatives.optJSONArray(i);
            if (choice == null || choice.length() == 0) continue;
            int matched = 0;
            while (matched < choice.length() && test(pkg, node, choice.optJSONArray(matched))) matched++;
            if (matched == choice.length()) return true;
        }
        return false;
    }

    private static boolean test(String pkg, SelectorNode node, JSONArray condition) {
        if (condition == null) return false;
        String field = condition.optString(0);
        if(!node.known(field))return false;
        String operator = condition.optString(1);
        switch (field) {
            case "id":
                return compare(node.getViewIdResourceName(), condition);
            case "resourceName":
                return compare(resourceName(node.getViewIdResourceName()), condition);
            case "vid":
                String id = node.getViewIdResourceName();
                return compare(id != null && id.startsWith(pkg + ":id/") ? resourceName(id) : null, condition);
            case "text":
                return compare(node.getText(), condition);
            case "desc":
                return compare(node.getContentDescription(), condition);
            case "text.length":
                return node.getText() != null && compare(node.getText().length(), operator, condition.optInt(2));
            case "desc.length":
                return node.getContentDescription() != null
                        && compare(node.getContentDescription().length(), operator, condition.optInt(2));
            case "childCount":
                return compare(node.getChildCount(), operator, condition.optInt(2));
            case "visibleToUser":
                return node.isVisibleToUser() == condition.optBoolean(2);
            case "clickable":
                return node.isClickable() == condition.optBoolean(2);
            default:
                return false;
        }
    }

    private static String resourceName(String id) {
        int separator = id == null ? -1 : id.indexOf(":id/");
        return separator > 0 ? id.substring(separator + 4) : null;
    }

    private static boolean compare(CharSequence value, JSONArray condition) {
        String actual = value == null ? null : value.toString();
        String expected = condition.isNull(2) ? null : condition.optString(2);
        switch (condition.optString(1)) {
            case "=": return Objects.equals(actual, expected);
            case "!=": return !Objects.equals(actual, expected);
            case "^=": return actual != null && expected != null && actual.startsWith(expected);
            case "$=": return actual != null && expected != null && actual.endsWith(expected);
            case "*=": return actual != null && expected != null && actual.contains(expected);
            default: return false;
        }
    }

    private static boolean compare(int actual, String operator, int expected) {
        switch (operator) {
            case "=": return actual == expected;
            case "<": return actual < expected;
            case "<=": return actual <= expected;
            case ">": return actual > expected;
            case ">=": return actual >= expected;
            default: return false;
        }
    }
}
