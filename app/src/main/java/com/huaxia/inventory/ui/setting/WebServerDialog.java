package com.huaxia.inventory.ui.setting;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.huaxia.inventory.R;
import com.huaxia.inventory.web.WebServerService;

import java.util.List;

/** Web 服务开关 + 地址展示。 */
public class WebServerDialog extends DialogFragment {

    private static final int DEFAULT_PORT = 8080;

    private SwitchMaterial switchServer;
    private TextView tvAddress;

    public static WebServerDialog newInstance() {
        return new WebServerDialog();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_web_server, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        switchServer = view.findViewById(R.id.switchWebServer);
        tvAddress = view.findViewById(R.id.tvWebServerAddress);
        MaterialButton close = view.findViewById(R.id.btnWebServerClose);

        switchServer.setChecked(WebServerService.isRunning());
        render();

        switchServer.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                requireContext().startForegroundService(
                        WebServerService.buildStartIntent(requireContext()));
            } else {
                requireContext().startService(
                        WebServerService.buildStopIntent(requireContext()));
            }
            // 服务启停是异步的，稍后刷新一次状态
            btn.postDelayed(this::render, 600);
        });

        close.setOnClickListener(v -> dismiss());
    }

    private void render() {
        boolean on = WebServerService.isRunning();
        switchServer.setChecked(on);
        switchServer.setText(on ? R.string.web_server_toggle_on : R.string.web_server_toggle_off);

        List<String> ips = WebServerService.localIpv4Addresses();
        if (ips.isEmpty()) {
            tvAddress.setText(R.string.web_server_no_network);
            return;
        }
        int port = WebServerService.activePort();
        String addr = ips.get(0) + ":" + (port > 0 ? port : DEFAULT_PORT);
        tvAddress.setText(getString(R.string.web_server_address_fmt, addr));
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
            }
        }
    }
}
