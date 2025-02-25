/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2023-2024 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.dialer.app.settings;

import static android.app.Activity.RESULT_OK;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.dialer.R;
import com.android.dialer.callrecord.impl.CallRecorderService;
import com.android.dialer.util.SettingsUtil;

public class SoundSettingsFragment extends PreferenceFragmentCompat
    implements Preference.OnPreferenceChangeListener {

  private static final String KEY_RECORDING_WARNING_PRESENTED = "recording_warning_presented";

  private static final String BUTTON_SMART_MUTE_KEY = "button_smart_mute";

  private static final int NO_DTMF_TONE = 0;
  private static final int PLAY_DTMF_TONE = 1;

  private static final int NO_VIBRATION_FOR_CALLS = 0;
  private static final int DO_VIBRATION_FOR_CALLS = 1;

  private static final int DTMF_TONE_TYPE_NORMAL = 0;

  private static final int MSG_UPDATE_RINGTONE_SUMMARY = 1;

  private DefaultRingtonePreference ringtonePreference;
  private final Handler ringtoneLookupComplete =
      new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
          switch (msg.what) {
            case MSG_UPDATE_RINGTONE_SUMMARY:
              ringtonePreference.setSummary((CharSequence) msg.obj);
              break;
          }
        }
      };
  private final Runnable ringtoneLookupRunnable = () -> updateRingtonePreferenceSummary();

  private final ActivityResultLauncher<Intent> mRingtonePickerResult = registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
              Intent data = result.getData();
              if (data == null || data.getExtras() == null) {
                return;
              }
              Uri uri = (Uri) data.getExtras().get(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
              ringtonePreference.onSaveRingtone(uri);
            }
          });

  private SwitchPreferenceCompat vibrateWhenRinging;
  private SwitchPreferenceCompat playDtmfTone;
  private ListPreference dtmfToneLength;
  private SwitchPreferenceCompat enableDndInCall;
  private SwitchPreferenceCompat callRecordAutostart;
  private SwitchPreferenceCompat smartMute;

  private NotificationManager notificationManager;

  @Override
  public Context getContext() {
    return getActivity();
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getPreferenceManager().setStorageDeviceProtected();
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    getPreferenceManager().setStorageDeviceProtected();
    addPreferencesFromResource(R.xml.sound_settings);

    Context context = getActivity();

    ringtonePreference = findPreference(context.getString(R.string.ringtone_preference_key));
    vibrateWhenRinging = findPreference(context.getString(R.string.vibrate_on_preference_key));
    playDtmfTone = findPreference(context.getString(R.string.play_dtmf_preference_key));
    dtmfToneLength = findPreference(context.getString(R.string.dtmf_tone_length_preference_key));
    enableDndInCall = findPreference("incall_enable_dnd");
    callRecordAutostart = findPreference(context.getString(R.string.call_recording_autostart_key));
    smartMute = findPreference(BUTTON_SMART_MUTE_KEY);

    if (hasVibrator()) {
      vibrateWhenRinging.setOnPreferenceChangeListener(this);
    } else {
      PreferenceScreen ps = getPreferenceScreen();
      Preference inCallVibrateOutgoing = findPreference(
              context.getString(R.string.incall_vibrate_outgoing_key));
      Preference inCallVibrateCallWaiting = findPreference(
              context.getString(R.string.incall_vibrate_call_waiting_key));
      Preference inCallVibrateHangup = findPreference(
              context.getString(R.string.incall_vibrate_hangup_key));
      Preference inCallVibrate45Secs = findPreference(
              context.getString(R.string.incall_vibrate_45_key));
      ps.removePreference(vibrateWhenRinging);
      ps.removePreference(inCallVibrateOutgoing);
      ps.removePreference(inCallVibrateCallWaiting);
      ps.removePreference(inCallVibrateHangup);
      ps.removePreference(inCallVibrate45Secs);
      vibrateWhenRinging = null;
    }

    playDtmfTone.setOnPreferenceChangeListener(this);
    playDtmfTone.setChecked(shouldPlayDtmfTone());

    enableDndInCall.setOnPreferenceChangeListener(this);

    TelephonyManager telephonyManager =
            (TelephonyManager) getActivity().getSystemService(Context.TELEPHONY_SERVICE);
    if (telephonyManager.canChangeDtmfToneLength()
            && (telephonyManager.isWorldPhone() || !shouldHideCarrierSettings())) {
      dtmfToneLength.setOnPreferenceChangeListener(this);
      dtmfToneLength.setValueIndex(
              Settings.System.getInt(
                      context.getContentResolver(),
                      Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                      DTMF_TONE_TYPE_NORMAL));
    } else {
      getPreferenceScreen().removePreference(dtmfToneLength);
      dtmfToneLength = null;
    }
    if (!CallRecorderService.isEnabled(getActivity())) {
      getPreferenceScreen().removePreference(
              findPreference(context.getString(R.string.call_recording_category_key)));
    } else {
      callRecordAutostart.setOnPreferenceChangeListener(this);
    }
    smartMute.setOnPreferenceChangeListener(this);
    notificationManager = context.getSystemService(NotificationManager.class);
  }

  @Override
  public void onResume() {
    super.onResume();

    if (!Settings.System.canWrite(getContext())) {
      // If the user launches this setting fragment, then toggles the WRITE_SYSTEM_SETTINGS
      // AppOp, then close the fragment since there is nothing useful to do.
      getActivity().onBackPressed();
      return;
    }

    if (vibrateWhenRinging != null) {
      vibrateWhenRinging.setChecked(shouldVibrateWhenRinging());
    }

    // Lookup the ringtone name asynchronously.
    new Thread(ringtoneLookupRunnable).start();
  }

  /**
   * Supports onPreferenceChangeListener to look for preference changes.
   *
   * @param preference The preference to be changed
   * @param objValue The value of the selection, NOT its localized display value.
   */
  @Override
  public boolean onPreferenceChange(Preference preference, Object objValue) {
    if (!Settings.System.canWrite(getContext())) {
      // A user shouldn't be able to get here, but this protects against monkey crashes.
      Toast.makeText(
              getContext(),
              getResources().getString(R.string.toast_cannot_write_system_settings),
              Toast.LENGTH_SHORT)
          .show();
      return true;
    }
    if (preference == vibrateWhenRinging) {
      boolean doVibrate = (Boolean) objValue;
      Settings.System.putInt(
          getActivity().getContentResolver(),
          Settings.System.VIBRATE_WHEN_RINGING,
          doVibrate ? DO_VIBRATION_FOR_CALLS : NO_VIBRATION_FOR_CALLS);
    } else if (preference == dtmfToneLength) {
      int index = dtmfToneLength.findIndexOfValue((String) objValue);
      Settings.System.putInt(
          getActivity().getContentResolver(), Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
    } else if (preference == enableDndInCall) {
      boolean newValue = (Boolean) objValue;
      if (newValue && !notificationManager.isNotificationPolicyAccessGranted()) {
        new AlertDialog.Builder(getContext())
            .setMessage(R.string.incall_dnd_dialog_message)
            .setPositiveButton(R.string.allow, (dialog, which) -> {
              dialog.dismiss();
              Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
              startActivity(intent);
            })
            .setNegativeButton(R.string.deny, (dialog, which) -> dialog.dismiss())
            .show();

        // At this time, it is unknown whether the user granted the permission
        return false;
      }
    } else if (preference == callRecordAutostart) {
      boolean newValue = (Boolean) objValue;
      if (newValue) {
        final SharedPreferences prefs =
                getPreferenceManager().getDefaultSharedPreferences(getContext());
        boolean warningPresented = prefs.getBoolean(KEY_RECORDING_WARNING_PRESENTED, false);
        if (!warningPresented) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.recording_warning_title)
                  .setMessage(R.string.recording_warning_text)
                  .setPositiveButton(R.string.onscreenCallRecordText, (dialog, which) -> {
                    prefs.edit()
                            .putBoolean(KEY_RECORDING_WARNING_PRESENTED, true)
                            .apply();
                    callRecordAutostart.setChecked(true);
                  })
                  .setNegativeButton(android.R.string.cancel, null)
                  .show();

          // At this time, it is unknown whether the user granted the permission
          return false;
        }
      }
    } else if (preference == smartMute) {
      boolean newValue = (Boolean) objValue;
      final SharedPreferences prefs =
              getPreferenceManager().getDefaultSharedPreferences(getContext());
      prefs
        .edit()
        .putBoolean(BUTTON_SMART_MUTE_KEY, newValue)
        .apply();
    }
    return true;
  }

  /** Click listener for toggle events. */
  @Override
  public boolean onPreferenceTreeClick(Preference preference) {
    if (!Settings.System.canWrite(getContext())) {
      Toast.makeText(
              getContext(),
              getResources().getString(R.string.toast_cannot_write_system_settings),
              Toast.LENGTH_SHORT)
          .show();
      return true;
    }
    if (preference == ringtonePreference) {
      mRingtonePickerResult.launch(ringtonePreference.getRingtonePickerIntent());
    } else if (preference == playDtmfTone) {
      Settings.System.putInt(
          getActivity().getContentResolver(),
          Settings.System.DTMF_TONE_WHEN_DIALING,
          playDtmfTone.isChecked() ? PLAY_DTMF_TONE : NO_DTMF_TONE);
    }
    return true;
  }

  /** Updates the summary text on the ringtone preference with the name of the ringtone. */
  private void updateRingtonePreferenceSummary() {
    SettingsUtil.updateRingtoneName(
        getActivity(),
        ringtoneLookupComplete,
        RingtoneManager.TYPE_RINGTONE,
        ringtonePreference.getKey(),
        MSG_UPDATE_RINGTONE_SUMMARY);
  }

  /**
   * Obtain the value for "vibrate when ringing" setting. The default value is false.
   *
   * <p>Watch out: if the setting is missing in the device, this will try obtaining the old "vibrate
   * on ring" setting from AudioManager, and save the previous setting to the new one.
   */
  private boolean shouldVibrateWhenRinging() {
    int vibrateWhenRingingSetting =
        Settings.System.getInt(
            requireActivity().getContentResolver(),
            Settings.System.VIBRATE_WHEN_RINGING,
            NO_VIBRATION_FOR_CALLS);
    return hasVibrator() && (vibrateWhenRingingSetting == DO_VIBRATION_FOR_CALLS);
  }

  /** Obtains the value for dialpad/DTMF tones. The default value is true. */
  private boolean shouldPlayDtmfTone() {
    int dtmfToneSetting =
        Settings.System.getInt(
            requireActivity().getContentResolver(),
            Settings.System.DTMF_TONE_WHEN_DIALING,
            PLAY_DTMF_TONE);
    return dtmfToneSetting == PLAY_DTMF_TONE;
  }

  /** Whether the device hardware has a vibrator. */
  private boolean hasVibrator() {
    Vibrator vibrator = requireActivity().getSystemService(Vibrator.class);
    return vibrator != null && vibrator.hasVibrator();
  }

  @SuppressLint("MissingPermission")
  private boolean shouldHideCarrierSettings() {
    CarrierConfigManager configManager =
        (CarrierConfigManager) requireActivity().getSystemService(Context.CARRIER_CONFIG_SERVICE);
    return configManager
        .getConfig()
        .getBoolean(CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL);
  }
}
