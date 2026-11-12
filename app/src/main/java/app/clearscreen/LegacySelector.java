package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.Pattern;

/** Matches compatible selectors against an accessibility node. */
final class LegacySelector {
    private static final Pattern BOUNDS_PREFIX = Pattern.compile("-?[0-9]+(?: *, *-?[0-9]+){2} *,.*");
    private static final Pattern IMPORT_BOUNDS = Pattern.compile("-?[0-9]{1,5}(?: *, *-?[0-9]{1,5}){3}(?: *, *[01])?");
    private final String[] terms;
    private final Rect bounds;
    private final int checked;
    private final String fullId, idSuffix;

    LegacySelector(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("规则缺少选择器");
        if (value.matches("-?[0-9]{1,5}(,-?[0-9]{1,5}){3}(,[01])?")) {
            String[] coordinates = value.split(",");
            bounds = new Rect(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]), Integer.parseInt(coordinates[3]));
            if (bounds.isEmpty()) throw new IllegalArgumentException("控件范围无效");
            checked = coordinates.length == 5 ? Integer.parseInt(coordinates[4]) : -1;
            terms = new String[0];
            fullId = "";
            idSuffix = "";
        } else {
            bounds = null;
            checked = -1;
            terms = value.split("&", -1);
            if (terms.length > 8) throw new IllegalArgumentException("选择条件过多");
            for (String term : terms) {
                if (term.trim().isEmpty() || "+-=".contains(term)) throw new IllegalArgumentException("选择条件不能为空");
            }
            String single = terms.length == 1 ? terms[0] : "";
            if (single.startsWith("=")) single = single.substring(1);
            fullId = single.matches("[A-Za-z][A-Za-z0-9_.]*:id/[A-Za-z0-9_]+") ? single : "";
            idSuffix = single.matches("[A-Za-z][A-Za-z0-9_]*") ? ":id/" + single : "";
        }
    }

    static String forImport(String value) {
        String trimmed = value.trim();
        if (!BOUNDS_PREFIX.matcher(trimmed).matches()) return value;
        if (!IMPORT_BOUNDS.matcher(trimmed).matches()) throw new IllegalArgumentException("控件范围格式无效");
        if (trimmed.split(",", -1).length == 5) throw new IllegalArgumentException("复选框状态规则受点击保护，已略过");
        return trimmed.replace(" ", "");
    }

    boolean usesBounds() { return bounds != null; }

    boolean matches(AccessibilityNodeInfo node) {
        if (bounds != null) {
            Rect actual = new Rect();
            node.getBoundsInScreen(actual);
            return bounds.equals(actual) && (checked < 0 || (node.isCheckable() && node.isChecked() == (checked == 1)));
        }
        String text = clean(node.getText());
        String desc = clean(node.getContentDescription());
        if (!fullId.isEmpty()) return matchesResource(node);
        if (matchesResource(node)) return true;
        return matchesText(text) || matchesText(desc);
    }

    boolean matchesResource(AccessibilityNodeInfo node) {
        String id = clean(node.getViewIdResourceName());
        return !fullId.isEmpty() ? id.equals(fullId) : !idSuffix.isEmpty() && id.endsWith(idSuffix);
    }

    private boolean matchesText(String text) {
        if (text.isEmpty()) return false;
        for (String term : terms) {
            boolean matched;
            switch (term.charAt(0)) {
                case '=': matched = text.equals(term.substring(1)); break;
                case '+': matched = text.startsWith(term.substring(1)); break;
                case '-': matched = text.endsWith(term.substring(1)); break;
                default: matched = text.contains(term); break;
            }
            if (!matched) return false;
        }
        return terms.length > 0;
    }

    private static String clean(CharSequence text) { return text == null ? "" : text.toString().trim(); }
}
