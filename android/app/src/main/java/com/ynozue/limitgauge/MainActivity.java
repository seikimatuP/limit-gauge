package com.ynozue.limitgauge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Status at a glance, connection settings, display options and setup help. */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1;
    /** Opening the app refreshes, but not more often than this. */
    private static final long AUTO_REFRESH_MIN_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView fiveValue, fiveDetail, weekValue, weekDetail, statusLine, errorLine, liveHint;
    private ProgressBar fiveBar, weekBar;
    private EditText urlInput, tokenInput;
    private CheckBox showToken;
    private RadioGroup modeGroup;
    private Switch notifySwitch, liveSwitch;
    private Button refreshButton;

    private boolean refreshing;
    /** Set while the UI is being filled from prefs, so listeners don't write back. */
    private boolean binding;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    renderStatus();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Android 15+ is edge-to-edge already; opt in explicitly on 12-14 so layout is the same.
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(R.layout.activity_main);
        applyInsets();

        fiveValue = findViewById(R.id.five_value);
        fiveDetail = findViewById(R.id.five_detail);
        fiveBar = findViewById(R.id.five_bar);
        weekValue = findViewById(R.id.week_value);
        weekDetail = findViewById(R.id.week_detail);
        weekBar = findViewById(R.id.week_bar);
        statusLine = findViewById(R.id.status_line);
        errorLine = findViewById(R.id.error_line);
        refreshButton = findViewById(R.id.refresh_button);
        urlInput = findViewById(R.id.url_input);
        tokenInput = findViewById(R.id.token_input);
        showToken = findViewById(R.id.show_token);
        modeGroup = findViewById(R.id.mode_group);
        notifySwitch = findViewById(R.id.notify_switch);
        liveSwitch = findViewById(R.id.live_switch);
        liveHint = findViewById(R.id.live_hint);

        bindSettings();
        wireListeners();

        TextView version = findViewById(R.id.version_text);
        version.setText(getString(R.string.version_fmt, versionName()));

        if (savedInstanceState == null) handleLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener);
        renderStatus();
        if (Prefs.configured(this)) {
            Scheduler.ensurePeriodic(this);
            long last = Prefs.get(this).getLong(Prefs.FETCHED_AT, 0L);
            if (System.currentTimeMillis() - last > AUTO_REFRESH_MIN_MS) refreshNow(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
    }

    // ---- Layout ----------------------------------------------------------------------------

    /** Edge-to-edge: pad the content by the system bars and the keyboard. */
    private void applyInsets() {
        final View content = findViewById(R.id.content);
        final int l = content.getPaddingLeft(), t = content.getPaddingTop();
        final int r = content.getPaddingRight(), b = content.getPaddingBottom();
        findViewById(R.id.scroll).setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                content.setPadding(l + bars.left, t + bars.top, r + bars.right, b + Math.max(bars.bottom, ime.bottom));
                if (ime.bottom > 0) {
                    // adjustResize no longer resizes edge-to-edge windows; bring the focused field into view.
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            View f = getCurrentFocus();
                            if (f != null) {
                                android.graphics.Rect rect = new android.graphics.Rect();
                                f.getDrawingRect(rect);
                                f.requestRectangleOnScreen(rect);
                            }
                        }
                    });
                }
                return WindowInsets.CONSUMED;
            }
        });
    }

    private void bindSettings() {
        binding = true;
        urlInput.setText(Prefs.url(this));
        tokenInput.setText(Prefs.token(this));
        modeGroup.check(Prefs.showUsed(this) ? R.id.mode_used : R.id.mode_left);
        notifySwitch.setChecked(Prefs.notifyEnabled(this) && Notifier.hasPermission(this));
        liveSwitch.setChecked(Prefs.liveUpdate(this));
        updateLiveControls();
        binding = false;
    }

    private void updateLiveControls() {
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
        liveSwitch.setEnabled(supported && notifySwitch.isChecked());
        liveHint.setVisibility(supported ? View.VISIBLE : View.GONE);
    }

    private void wireListeners() {
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Prefs.configured(MainActivity.this)) {
                    toast(getString(R.string.status_not_configured));
                    return;
                }
                refreshNow(true);
            }
        });
        findViewById(R.id.save_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });
        findViewById(R.id.paste_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteSetupLink();
            }
        });
        showToken.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                int sel = tokenInput.getSelectionEnd();
                tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                tokenInput.setSelection(Math.max(0, Math.min(sel, tokenInput.length())));
            }
        });
        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (binding) return;
                Prefs.get(MainActivity.this).edit().putBoolean(Prefs.SHOW_USED, checkedId == R.id.mode_used).apply();
                Refresher.renderAll(MainActivity.this);
                renderStatus();
            }
        });
        notifySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (binding) return;
                if (checked && !Notifier.hasPermission(MainActivity.this)) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                    return; // saved once the user answers
                }
                setNotify(checked);
            }
        });
        liveSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (binding) return;
                Prefs.get(MainActivity.this).edit().putBoolean(Prefs.LIVE_UPDATE, checked).apply();
                Notifier.update(MainActivity.this);
            }
        });
        liveHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLiveUpdateSettings();
            }
        });
        findViewById(R.id.add_card).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinWidget(CardWidgetProvider.class);
            }
        });
        findViewById(R.id.add_week).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinWidget(WeeklyGaugeProvider.class);
            }
        });
        findViewById(R.id.add_five).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinWidget(FiveHourGaugeProvider.class);
            }
        });
    }

    // ---- Status ----------------------------------------------------------------------------

    private void renderStatus() {
        long now = Formats.nowSec();
        UsageData d = Prefs.data(this);
        boolean showUsed = Prefs.showUsed(this);
        renderWindow(fiveValue, fiveBar, fiveDetail, d == null ? null : d.fiveHour, showUsed, now);
        renderWindow(weekValue, weekBar, weekDetail, d == null ? null : d.sevenDay, showUsed, now);

        SharedPreferences p = Prefs.get(this);
        StringBuilder sb = new StringBuilder();
        if (!Prefs.configured(this)) {
            sb.append(getString(R.string.status_not_configured));
        } else {
            if (d != null && d.updatedAt > 0) {
                sb.append(getString(R.string.status_data_time, Formats.when(this, d.updatedAt, now)));
            }
            long fetched = p.getLong(Prefs.FETCHED_AT, 0L);
            if (fetched > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(getString(R.string.status_fetched, Formats.when(this, fetched / 1000L, now)));
            }
            if (d != null && d.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(getString(R.string.status_waiting));
            }
        }
        statusLine.setText(sb);

        String error = p.getString(Prefs.ERROR, null);
        if (error == null || !Prefs.configured(this)) {
            errorLine.setVisibility(View.GONE);
        } else {
            errorLine.setVisibility(View.VISIBLE);
            errorLine.setText(getString(R.string.status_error, error));
        }
    }

    private void renderWindow(TextView value, ProgressBar bar, TextView detail, UsageData.Window w,
                              boolean showUsed, long now) {
        if (w == null) {
            value.setText(R.string.value_none);
            bar.setProgress(0);
            detail.setText(R.string.sub_no_data);
            return;
        }
        int left = w.leftPercent(now);
        int shown = showUsed ? 100 - left : left;
        value.setText(Formats.value(this, shown, showUsed));
        bar.setProgress(shown);
        bar.setProgressTintList(ColorStateList.valueOf(
                getColor(left < WidgetRenderer.LOW_LEFT ? R.color.lg_critical : R.color.lg_accent)));
        detail.setText(Formats.resetDetail(this, w, now));
    }

    // ---- Actions ---------------------------------------------------------------------------

    private void refreshNow(final boolean userInitiated) {
        if (refreshing) return;
        refreshing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText(R.string.refreshing);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String error = Refresher.refresh(app);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        if (isFinishing() || isDestroyed()) return;
                        refreshButton.setEnabled(true);
                        refreshButton.setText(R.string.refresh_now);
                        renderStatus();
                        if (userInitiated) toast(error == null ? getString(R.string.toast_updated) : error);
                    }
                });
            }
        }, "limit-gauge-ui-refresh").start();
    }

    private void saveSettings() {
        String url = urlInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if (!SetupLink.isValidUrl(url)) {
            urlInput.setError(getString(R.string.err_url_scheme));
            return;
        }
        if (!SetupLink.isValidToken(token)) {
            tokenInput.setError(getString(R.string.err_token_empty));
            return;
        }
        applySettings(url, token);
    }

    private void applySettings(String url, String token) {
        boolean changed = !url.equals(Prefs.url(this)) || !token.equals(Prefs.token(this));
        SharedPreferences.Editor e = Prefs.get(this).edit()
                .putString(Prefs.URL, url)
                .putString(Prefs.TOKEN, token);
        if (changed) {
            // Data from a different relay must not linger.
            e.remove(Prefs.DATA).remove(Prefs.FETCHED_AT).remove(Prefs.ERROR).remove(Prefs.ERROR_AT);
        }
        e.commit();
        binding = true;
        urlInput.setText(url);
        tokenInput.setText(token);
        binding = false;
        urlInput.setError(null);
        tokenInput.setError(null);
        hideKeyboard();
        Scheduler.ensurePeriodic(this);
        Refresher.renderAll(this);
        refreshNow(true);
    }

    private void pasteSetupLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast(getString(R.string.paste_empty));
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        SetupLink link = SetupLink.parse(text == null ? null : text.toString());
        if (link == null) {
            toast(getString(R.string.paste_invalid));
            return;
        }
        // Any page or app can put a link on the clipboard, so this is confirmed like a deep link.
        confirmLink(link);
    }

    /** limitgauge://config?u=...&t=... from the pairing page. Always confirmed before saving. */
    private void handleLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        SetupLink link = SetupLink.parse(intent.getData().toString());
        if (link == null) {
            toast(getString(R.string.link_invalid));
            return;
        }
        confirmLink(link);
    }

    private void confirmLink(final SetupLink link) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.link_confirm_title)
                .setMessage(getString(R.string.link_confirm_message, SetupLink.displayOrigin(link.url)))
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applySettings(link.url, link.token);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setNotify(boolean on) {
        Prefs.get(this).edit().putBoolean(Prefs.NOTIFY, on).apply();
        updateLiveControls();
        Notifier.update(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        binding = true;
        notifySwitch.setChecked(granted);
        binding = false;
        if (!granted) toast(getString(R.string.notif_denied));
        setNotify(granted);
    }

    private void openLiveUpdateSettings() {
        Intent promo = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(promo);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            try {
                startActivity(fallback);
            } catch (ActivityNotFoundException ignored) {
                toast(getString(R.string.settings_unavailable));
            }
        }
    }

    private void pinWidget(Class<?> provider) {
        AppWidgetManager m = getSystemService(AppWidgetManager.class);
        if (m != null && m.isRequestPinAppWidgetSupported()) {
            m.requestPinAppWidget(new ComponentName(this, provider), null, null);
        } else {
            toast(getString(R.string.pin_unsupported));
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
