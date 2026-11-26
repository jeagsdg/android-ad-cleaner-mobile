"""Make a separate debug-only source copy for counting active-root requests."""
import argparse
import re
import shutil
from pathlib import Path


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('source', type=Path)
    cli.add_argument('output', type=Path)
    args = cli.parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    if output == source or output.is_relative_to(source):
        cli.error('Choose a fresh directory outside the original checkout')
    output.mkdir(parents=True, exist_ok=False)
    for name in ['app', 'fixture', 'gradle']:
        shutil.copytree(source / name, output / name, ignore=shutil.ignore_patterns('build', '.gradle'))
    for name in ['settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'local.properties']:
        if (source / name).exists():
            shutil.copy2(source / name, output / name)

    skipper = output / 'app/src/main/java/app/clearscreen/SplashSkipper.java'
    text = skipper.read_text(encoding='utf-8')
    call = 'service.getRootInActiveWindow()'
    marker = 'class SplashSkipper {'
    if marker not in text or call not in text:
        raise ValueError('Production scanner layout changed; review the probe before measuring')
    sites = text.count(call)
    text = text.replace(call, 'readActiveRoot(service)')
    text, click_sites = re.subn(r'(\b[\w.]+)\.performAction\(AccessibilityNodeInfo\.ACTION_CLICK\)',
                                r'benchmarkClick(\1)', text)
    if not click_sites:
        raise ValueError('No scanner action sites found')
    text = text.replace(marker, marker + '''
    static final java.util.concurrent.atomic.AtomicInteger rootReadProbe =
            new java.util.concurrent.atomic.AtomicInteger();
    private static AccessibilityNodeInfo readActiveRoot(AccessibilityService service) {
        rootReadProbe.incrementAndGet();
        return service.getRootInActiveWindow();
    }
    private static boolean benchmarkClick(AccessibilityNodeInfo node) {
        long time = android.os.SystemClock.elapsedRealtimeNanos();
        boolean accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (accepted && "app.clearscreen.testfixture".contentEquals(String.valueOf(node.getPackageName())))
            android.util.Log.i("ClearScreenBench", "action " + time);
        return accepted;
    }
''', 1)
    skipper.write_text(text, encoding='utf-8')

    fixture = output / 'fixture/src/main/java/app/clearscreen/testfixture/SplashActivity.java'
    text = fixture.read_text(encoding='utf-8')
    replacements = {
        'private void benchmarkResult(TextView content, long shown) {':
            '''private void observeReady(android.view.View view, String mode) {
        view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                if (view.isShown() && view.isEnabled() && view.isClickable()) {
                    view.getViewTreeObserver().removeOnPreDrawListener(this);
                    android.util.Log.i("ClearScreenBench", "ready " + android.os.SystemClock.elapsedRealtimeNanos() + " " + mode);
                }
                return true;
            }
        });
    }

    private void benchmarkResult(TextView content, long shown) {
        android.util.Log.i("ClearScreenBench", "callback " + android.os.SystemClock.elapsedRealtimeNanos());''',
        'long shown = SystemClock.elapsedRealtime();\n                new AlertDialog.Builder':
            'long shown = SystemClock.elapsedRealtime();\n                AlertDialog dialog = new AlertDialog.Builder',
        '.show();\n            }, 500);': '.show();\n                observeReady(dialog.getButton(-2), mode);\n            }, 500);',
        'page.addView(skip, position);\n        };':
            'page.addView(skip, position);\n            observeReady(skip, mode);\n        };',
    }
    for anchor, replacement in replacements.items():
        if text.count(anchor) != 1:
            raise ValueError('Fixture availability timing changed; review the probe before measuring')
        text = text.replace(anchor, replacement, 1)
    fixture.write_text(text, encoding='utf-8')

    checks = output / 'app/src/androidTest/java/app/clearscreen/DeviceChecks.java'
    shutil.copy2(Path(__file__).resolve().parent / 'benchmark/ProbeDeviceChecks.java', checks.parent)
    text = checks.read_text(encoding='utf-8')
    edits = {
        '        if (suite.equals("ad-latency")) {': '''        if (suite.equals("idle-ready") || suite.equals("screen-off")) {
            ProbeDeviceChecks.run(this, automation, connectService(), suite.equals("screen-off"));
            result.putString("stream", "PASS: " + assertions + " " + suite + " benchmark assertions");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("ad-latency")) {''',
        'List<Long> times=new ArrayList<>();': 'List<Long> times=new ArrayList<>();\n'
            '                List<Integer> reads=new ArrayList<>();',
        'launchPopup(mode);\n                    long millis=waitForAdResult':
            'int readsBefore=SplashSkipper.rootReadProbe.get();\n'
            '                    launchPopup(mode);\n                    long millis=waitForAdResult',
        'Thread.sleep(50);\n                }\n                Collections.sort(times);':
            'Thread.sleep(50);\n                    reads.add(SplashSkipper.rootReadProbe.get()-readsBefore);\n'
            '                }\n                Collections.sort(times);',
        'report.append(mode).append(\' \').append(times.size())':
            'report.append(mode).append(" active-root-reads=").append(reads).append("\\n");\n'
            '                report.append(mode).append(\' \').append(times.size())',
    }
    for anchor, replacement in edits.items():
        if text.count(anchor) != 1:
            raise ValueError('Device latency fixture changed; review the probe before measuring')
        text = text.replace(anchor, replacement, 1)
    checks.write_text(text, encoding='utf-8')
    print(f'{sites} active-root request sites instrumented in {output}')


if __name__ == '__main__':
    main()
