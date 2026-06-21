package com.huaxia.inventory.ui.manual;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.huaxia.inventory.R;
import com.huaxia.inventory.barcode.BarcodeCameraController;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.List;

public class ManualScanFragment extends Fragment {

    private ManualScanViewModel vm;
    private static final String TAG = "ManualScan";
    private LinearLayout scanStatusCard;
    private TextView scanStatusIconTv, scanStatusTextTv, scanStatusTipTv;
    private MaterialButton startScanBtn, stopScanBtn;
    private FrameLayout cameraContainer;
    private PreviewView previewView;
    private BarcodeCameraController barcodeController;
    private TextInputEditText inputEt;
    private TextView tipTv;
    private CardView detailCard;
    private TextView detailEpcTv, detailNameTv, detailOrgTv, detailStatusTv;
    private MaterialButton confirmCheckBtn;
    private LinearLayout historyList;
    private TextView historyEmptyTv;

    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manual_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ManualScanViewModel.class);
        barcodeController = new BarcodeCameraController();

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    Log.i(TAG, "[ManualScanFragment] camera permission result granted=" + granted);
                    if (granted) {
                        startCamera();
                    } else {
                        Snackbar.make(requireView(),
                                R.string.manual_scan_camera_permission_denied,
                                Snackbar.LENGTH_LONG).show();
                        // 用户拒了权限, 把 UI 状态回退到「等待扫码」, 不卡在 active.
                        forceIdleState();
                    }
                });

        scanStatusCard = v.findViewById(R.id.scanStatusCard);
        scanStatusIconTv = v.findViewById(R.id.scanStatusIconTv);
        scanStatusTextTv = v.findViewById(R.id.scanStatusTextTv);
        scanStatusTipTv = v.findViewById(R.id.scanStatusTipTv);
        startScanBtn = v.findViewById(R.id.startScanBtn);
        stopScanBtn = v.findViewById(R.id.stopScanBtn);
        cameraContainer = v.findViewById(R.id.cameraContainer);
        previewView = v.findViewById(R.id.previewView);
        inputEt = v.findViewById(R.id.inputEpEt);
        tipTv = v.findViewById(R.id.tipTv);
        detailCard = v.findViewById(R.id.detailCard);
        detailEpcTv = v.findViewById(R.id.detailEpcTv);
        detailNameTv = v.findViewById(R.id.detailNameTv);
        detailOrgTv = v.findViewById(R.id.detailOrgTv);
        detailStatusTv = v.findViewById(R.id.detailStatusTv);
        confirmCheckBtn = v.findViewById(R.id.confirmCheckBtn);
        historyList = v.findViewById(R.id.historyList);
        historyEmptyTv = v.findViewById(R.id.historyEmptyTv);

        inputEt.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                triggerQuery();
                return true;
            }
            return false;
        });
        v.findViewById(R.id.queryBtn).setOnClickListener(btn -> triggerQuery());
        startScanBtn.setOnClickListener(btn -> startScan());
        stopScanBtn.setOnClickListener(btn -> stopScan());
        v.findViewById(R.id.backBtn).setOnClickListener(btn -> {
            stopScan();
            requireActivity().getSupportFragmentManager().popBackStack();
        });
        confirmCheckBtn.setOnClickListener(btn -> {
            Asset current = currentAsset;
            if (current != null) vm.confirmCheck(current, currentNormalizedEpc);
        });

        vm.lookup().observe(getViewLifecycleOwner(), this::renderLookup);
        vm.checkEvent().observe(getViewLifecycleOwner(), this::renderCheckEvent);
        vm.scanEvent().observe(getViewLifecycleOwner(), this::renderScanEvent);
        vm.history().observe(getViewLifecycleOwner(), this::renderHistory);
    }

    @Override
    public void onResume() {
        super.onResume();
        vm.refreshHistory();
    }

    @Override
    public void onPause() {
        super.onPause();
        // 离开页面时强制退出扫码模式, 避免输入框保留焦点/键盘弹起残留到别的页面.
        stopScan();
    }

    /**
     * 进入扫码模式: UI 切到「正在扫码」, 启动/停止按钮互换, 显示摄像头预览.
     * 检测到权限 → startCamera; 没权限 → 弹系统授权, 结果由 cameraPermissionLauncher 接.
     */
    private void startScan() {
        Log.i(TAG, "[ManualScanFragment] startScan() pressed");
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Log.i(TAG, "[ManualScanFragment] CAMERA not granted, requesting");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        cameraContainer.setVisibility(View.VISIBLE);
        scanStatusCard.setBackgroundResource(R.drawable.bg_scan_status_active);
        scanStatusIconTv.setText(R.string.manual_scan_active_icon);
        scanStatusTextTv.setText(R.string.manual_scan_active);
        scanStatusTextTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        scanStatusTipTv.setText(R.string.manual_scan_active_tip);
        startScanBtn.setVisibility(View.GONE);
        stopScanBtn.setVisibility(View.VISIBLE);

        boolean ok = barcodeController.startScan(this, previewView, rawCode ->
                vm.onBarcodeScanned(rawCode));
        if (!ok) {
            Snackbar.make(requireView(),
                    R.string.manual_scan_camera_init_failed,
                    Snackbar.LENGTH_LONG).show();
            forceIdleState();
        }
    }

    private void stopScan() {
        Log.i(TAG, "[ManualScanFragment] stopScan() pressed");
        if (barcodeController != null) barcodeController.stopScan();
        forceIdleState();
        cameraContainer.setVisibility(View.GONE);
        inputEt.setText("");
        View focused = requireActivity().getCurrentFocus();
        if (focused != null) {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    /** UI-only fallback when we couldn't actually start the camera (perm denied,
     *  device has no camera, bindToLifecycle failed). Keeps button states
     *  consistent without leaving the user stuck on the "正在扫码" card. */
    private void forceIdleState() {
        scanStatusCard.setBackgroundResource(R.drawable.bg_scan_status_default);
        scanStatusIconTv.setText(R.string.manual_scan_idle_icon);
        scanStatusTextTv.setText(R.string.manual_scan_idle);
        scanStatusTextTv.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black));
        scanStatusTipTv.setText(R.string.manual_scan_idle_tip);
        startScanBtn.setVisibility(View.VISIBLE);
        stopScanBtn.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (barcodeController != null) {
            barcodeController.release();
            barcodeController = null;
        }
    }

    private Asset currentAsset;
    private String currentNormalizedEpc;

    private void triggerQuery() {
        CharSequence cs = inputEt.getText();
        vm.queryByEpc(cs == null ? null : cs.toString());
    }

    private void renderLookup(@Nullable ManualScanViewModel.LookupResult r) {
        if (r == null) return;
        if (r.invalid) {
            showTip(getString(R.string.manual_err_invalid), TipKind.ERROR);
            hideDetail();
            return;
        }
        if (r.notFound) {
            String msg = getString(R.string.manual_err_not_found,
                    r.normalizedEpc == null
                            ? (inputEt.getText() == null ? "" : inputEt.getText().toString())
                            : r.normalizedEpc);
            showTip(msg, TipKind.ERROR);
            hideDetail();
            return;
        }
        hideTip();
        showDetail(r.asset, r.normalizedEpc);
    }

    private void renderCheckEvent(@Nullable ManualScanViewModel.CheckEvent e) {
        if (e == null) return;
        switch (e.outcome) {
            case CHECKED:
                Snackbar.make(requireView(),
                        getString(R.string.manual_msg_check_done,
                                e.epc == null ? "" : EpcNormalizer.toDisplayForm(e.epc)),
                        Snackbar.LENGTH_SHORT).show();
                inputEt.setText("");
                hideDetail();
                vm.refreshHistory();
                break;
            case DUPLICATE:
                String at = e.previousAt == null ? "—" : DateUtil.formatTimestamp(e.previousAt);
                Snackbar.make(requireView(),
                        getString(R.string.manual_msg_check_dup, at),
                        Snackbar.LENGTH_LONG).show();
                break;
            case NO_TASK:
                Snackbar.make(requireView(), R.string.manual_err_no_task,
                        Snackbar.LENGTH_SHORT).show();
                break;
            default:
                // INVALID_FORMAT / ASSET_NOT_FOUND go through renderLookup, not events.
                break;
        }
    }

    /**
     * Auto-scan path: barcode gun callback → onBarcodeScanned. No detail card,
     * no user confirmation — just toast and refresh history. Distinct from
     * {@link #renderCheckEvent} which serves the manual-input path.
     */
    private void renderScanEvent(@Nullable ManualScanViewModel.ScanEvent e) {
        if (e == null) return;
        String display = EpcNormalizer.toDisplayForm(e.epc);
        String msg;
        switch (e.outcome) {
            case SCANNED_FOUND:
                msg = getString(R.string.manual_scan_done_found,
                        display == null ? "" : display);
                break;
            case SCANNED_EXTRA:
                msg = getString(R.string.manual_scan_done_extra,
                        display == null ? "" : display);
                break;
            case SCANNED_DUPLICATE:
                String at = e.previousAt == null ? "—" : DateUtil.formatTimestamp(e.previousAt);
                msg = getString(R.string.manual_scan_done_dup,
                        display == null ? "" : display, at);
                break;
            case SCANNED_INVALID:
                msg = getString(R.string.manual_scan_done_invalid,
                        e.rawCode == null ? "" : e.rawCode);
                break;
            case SCANNED_NO_TASK:
                msg = getString(R.string.manual_err_no_task);
                break;
            default:
                return;
        }
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show();
    }

    private void renderHistory(@Nullable List<ScanRecord> records) {
        historyList.removeAllViews();
        if (records == null || records.isEmpty()) {
            historyEmptyTv.setVisibility(View.VISIBLE);
            return;
        }
        historyEmptyTv.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (ScanRecord r : records) {
            View row = inflater.inflate(R.layout.view_manual_history_row, historyList, false);
            ((TextView) row.findViewById(R.id.historyEpcTv))
                    .setText(EpcNormalizer.toDisplayForm(r.epc));
            ((TextView) row.findViewById(R.id.historyTimeTv))
                    .setText(DateUtil.formatTimestamp(r.scannedAt));
            TextView tag = row.findViewById(R.id.historyStatusTv);
            if (r.matchedAssetId == null) {
                tag.setText(R.string.tag_extra);
                tag.setBackgroundResource(R.drawable.bg_tag_orange);
                tag.setTextColor(ContextCompat.getColor(requireContext(), R.color.tagOrangeFg));
            } else {
                tag.setText(R.string.tag_found);
                tag.setBackgroundResource(R.drawable.bg_tag_green);
                tag.setTextColor(ContextCompat.getColor(requireContext(), R.color.tagGreenFg));
            }
            historyList.addView(row);
        }
    }

    private enum TipKind { INFO, ERROR, WARNING }

    private void showTip(String text, TipKind kind) {
        tipTv.setVisibility(View.VISIBLE);
        tipTv.setText(text);
        int bg;
        int fg;
        switch (kind) {
            case ERROR:
                bg = R.drawable.bg_tip_error; fg = R.color.tipErrorFg; break;
            case WARNING:
                bg = R.drawable.bg_tip_warning; fg = R.color.tipWarningFg; break;
            default:
                bg = R.drawable.bg_tip_info; fg = R.color.tipInfoFg; break;
        }
        tipTv.setBackgroundResource(bg);
        tipTv.setTextColor(ContextCompat.getColor(requireContext(), fg));
    }

    private void hideTip() { tipTv.setVisibility(View.GONE); }

    private void showDetail(@NonNull Asset a, @Nullable String normalizedEpc) {
        currentAsset = a;
        currentNormalizedEpc = normalizedEpc != null ? normalizedEpc : a.epc;
        detailEpcTv.setText(EpcNormalizer.toDisplayForm(currentNormalizedEpc));
        detailNameTv.setText(a.assetName == null || a.assetName.isEmpty()
                ? getString(R.string.manual_asset_status_unknown) : a.assetName);
        detailOrgTv.setText(a.org == null || a.org.isEmpty()
                ? getString(R.string.manual_asset_status_unknown) : a.org);
        detailStatusTv.setText(a.assetStatus == null || a.assetStatus.isEmpty()
                ? getString(R.string.manual_asset_status_unknown) : a.assetStatus);
        detailCard.setVisibility(View.VISIBLE);
        confirmCheckBtn.setEnabled(true);
    }

    private void hideDetail() {
        currentAsset = null;
        currentNormalizedEpc = null;
        detailCard.setVisibility(View.GONE);
        confirmCheckBtn.setEnabled(false);
    }
}