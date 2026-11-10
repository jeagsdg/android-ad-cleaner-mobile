package app.clearscreen.testfixture;

import android.os.Bundle;
import android.widget.Button;

/** A first-launch consent screen that the cleaner must leave untouched. */
public final class ConsentActivity extends FixtureActivity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        page.removeAllViews();
        Button consent = new Button(this);
        consent.setText("同意并继续");
        consent.setOnClickListener(view -> addSwitch("个性化推荐", true));
        page.addView(consent);
    }
}
