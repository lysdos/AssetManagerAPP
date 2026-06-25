package com.huaxia.inventory.ui.setting;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;

/**
 * RFID功率设置对话框。
 *
 * <p>提供SeekBar滑块和预设按钮来调节RFID读写器的功率，支持0-30dBm范围。</p>
 */
public class PowerSettingDialog extends DialogFragment {

    private static final String TAG = "PowerSetting";

    private TextView tvPowerValue;
    private SeekBar seekBarPower;
    private MaterialButton btnPreset5;
    private MaterialButton btnPreset15;
    private MaterialButton btnPreset26;
    private MaterialButton btnPreset30;

    private int currentPower;
    private OnPowerChangedListener listener;

    /**
     * 功率变更回调接口。
     */
    public interface OnPowerChangedListener {
        void onPowerChanged(int power);
    }

    public static PowerSettingDialog newInstance() {
        return new PowerSettingDialog();
    }

    public void setOnPowerChangedListener(OnPowerChangedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_power_setting, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadCurrentPower();
        setupListeners();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(android.view.Gravity.CENTER);
                window.setBackgroundDrawableResource(android.R.color.transparent);

                // 设置窗口软键盘模式，避免遮盖内容
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
    }

    private void initViews(View view) {
        tvPowerValue = view.findViewById(R.id.tvPowerValue);
        seekBarPower = view.findViewById(R.id.seekBarPower);
        btnPreset5 = view.findViewById(R.id.btnPreset5);
        btnPreset15 = view.findViewById(R.id.btnPreset15);
        btnPreset26 = view.findViewById(R.id.btnPreset26);
        btnPreset30 = view.findViewById(R.id.btnPreset30);

        ImageButton btnClose = view.findViewById(R.id.btnClose);
        MaterialButton btnCancel = view.findViewById(R.id.btnCancel);
        MaterialButton btnSave = view.findViewById(R.id.btnSave);

        btnClose.setOnClickListener(v -> dismiss());
        btnCancel.setOnClickListener(v -> dismiss());
        btnSave.setOnClickListener(v -> savePower());
    }

    private void loadCurrentPower() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();
        currentPower = app.getSavedPower();
        updateUI(currentPower);
    }

    private void setupListeners() {
        setupSeekBarListener();

        btnPreset5.setOnClickListener(v -> setPreset(5));
        btnPreset15.setOnClickListener(v -> setPreset(15));
        btnPreset26.setOnClickListener(v -> setPreset(26));
        btnPreset30.setOnClickListener(v -> setPreset(30));
    }

    private void setPreset(int power) {
        seekBarPower.setProgress(power);
        updateUI(power);
    }

    private void updateUI(int power) {
        currentPower = power;
        tvPowerValue.setText(String.valueOf(power));

        // 同步滑块位置（避免循环触发）
        seekBarPower.setOnSeekBarChangeListener(null);
        seekBarPower.setProgress(power);
        setupSeekBarListener();

        // 更新预设按钮状态
        updatePresetButton(btnPreset5, power == 5);
        updatePresetButton(btnPreset15, power == 15);
        updatePresetButton(btnPreset26, power == 26);
        updatePresetButton(btnPreset30, power == 30);
    }

    private void setupSeekBarListener() {
        seekBarPower.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updateUI(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 不需要处理
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 不需要处理
            }
        });
    }

    private void updatePresetButton(MaterialButton button, boolean isActive) {
        if (isActive) {
            button.setBackgroundTintList(getResources().getColorStateList(R.color.colorPrimary));
            button.setTextColor(getResources().getColor(android.R.color.white));
        } else {
            button.setBackgroundTintList(null);
            button.setTextColor(getResources().getColor(R.color.colorPrimary));
        }
    }

    private void savePower() {
        InventoryApp app = (InventoryApp) requireActivity().getApplication();

        // 先应用到 reader, 成功后才落 prefs. 失败时 prefs 不写, 避免出现
        // "prefs 里是新值但 reader 不知道" 的不一致状态.
        boolean success = app.rfid.setPower(currentPower);

        if (success) {
            app.savePower(currentPower);
            Toast.makeText(getContext(),
                    getString(R.string.power_setting_saved, currentPower),
                    Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Power saved and applied: " + currentPower + " dBm");

            // 通知监听器
            if (listener != null) {
                listener.onPowerChanged(currentPower);
            }

            dismiss();
        } else {
            Toast.makeText(getContext(), R.string.power_setting_failed,
                    Toast.LENGTH_SHORT).show();
            Log.w(TAG, "Failed to apply power: " + currentPower);
        }
    }
}
