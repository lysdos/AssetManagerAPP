package com.huaxia.inventory.ui.manual;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
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
import com.huaxia.inventory.barcode.BarcodeHardwareController;
import com.huaxia.inventory.barcode.ScanFrameView;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.BeepPlayer;
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
    private ScanFrameView scanFrameOverlay;
    private BarcodeCameraController barcodeController;
    private BarcodeHardwareController hardwareController;
    private View scanModePlaceholder;
    private TextView placeholderIconTv, placeholderTitleTv, placeholderTipTv;
    private boolean useHardware = false;
    private TextInputEditText inputEt;
    private TextView tipTv;
    private CardView detailCard;
    private TextView detailEpcTv, detailNameTv, detailOrgTv, detailStatusTv;
    private MaterialButton confirmCheckBtn;
    private LinearLayout historyList;
    private TextView historyEmptyTv;

    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private final BeepPlayer beepPlayer = new BeepPlayer();

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
                    // 系统权限回调可能在 fragment 已 detach 时派发
                    // (用户在系统对话框上停留较久, 期间通过底部 nav 切走).
                    // requireView()/requireContext() 在 onDestroyView 之后会抛
                    // IllegalStateException, 此处先 guard.
                    if (!isAdded()) {
                        Log.w(TAG, "permission callback fired after detach, ignored");
                        return;
                    }
                    Log.i(TAG, "[ManualScanFragment] camera permission result granted=" + granted);
                    if (granted) {
                        startCamera();
                    } else {
                        // 永久拒绝 (勾了"不再询问" / 策略禁止) 时 shouldShow...Rationale
                        // 返回 false —— 系统不会再弹授权框, 只能引导去设置手动开; 否则用户
                        // 永远卡在"需权限"提示、功能不可达.
                        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                            Snackbar.make(requireView(),
                                    R.string.manual_scan_camera_permission_denied_settings,
                                    Snackbar.LENGTH_LONG)
                                    .setAction(R.string.action_go_settings,
                                            sv -> openAppSettings())
                                    .show();
                        } else {
                            Snackbar.make(requireView(),
                                    R.string.manual_scan_camera_permission_denied,
                                    Snackbar.LENGTH_LONG).show();
                        }
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
        scanFrameOverlay = v.findViewById(R.id.scanFrameOverlay);
        scanModePlaceholder = v.findViewById(R.id.scanModePlaceholder);
        placeholderIconTv = v.findViewById(R.id.placeholderIconTv);
        placeholderTitleTv = v.findViewById(R.id.placeholderTitleTv);
        placeholderTipTv = v.findViewById(R.id.placeholderTipTv);
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
        vm.checkEvent().observe(getViewLifecycleOwner(), ev -> {
            if (ev == null) return;
            ManualScanViewModel.CheckEvent e = ev.getContentIfNotHandled();
            if (e != null) renderCheckEvent(e);
        });
        vm.scanEvent().observe(getViewLifecycleOwner(), ev -> {
            if (ev == null) return;
            ManualScanViewModel.ScanEvent e = ev.getContentIfNotHandled();
            if (e != null) renderScanEvent(e);
        });
        vm.history().observe(getViewLifecycleOwner(), this::renderHistory);

        beepPlayer.init(requireContext(), R.raw.barcodebeep);
    }

    /**
     * PDA 扳机联动: 扳机按下 = toggle 启动/停止扫码. 当前已开启 → 停;
     * 未开启 → 启. 触发源是 MainActivity.dispatchKeyEvent.
     */
    public void onTriggerPressed() {
        if (cameraContainer == null) return;
        if (cameraContainer.getVisibility() == View.VISIBLE) {
            stopScan();
        } else {
            startScan();
        }
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
     * 进入扫码模式: 双路径分发 — 硬件 SDK 优先, CameraX 兜底.
     * 硬件 SDK 在 InventoryApp.onCreate 已经预热过, 这里直接判 {@link BarcodeHardwareController#isReady()}.
     */
    private void startScan() {
        Log.i(TAG, "[ManualScanFragment] startScan() pressed");
        if (BarcodeHardwareController.isReady()) {
            Log.i(TAG, "[ManualScanFragment] using hardware barcode decoder (urovo ScanManager)");
            startHardwareScan();
        } else {
            Log.i(TAG, "[ManualScanFragment] hardware not ready, falling back to CameraX");
            startCameraWithPermissionCheck();
        }
    }

    /** 硬件扫码路径: 硬件扫码 ~50-200ms 出结果, 不需要"扫码中"视觉锚点,
     *  完全隐藏 cameraContainer 让下方输入框/查询按钮上移, 节省 260dp 户外 PDA 屏幕. */
    private void startHardwareScan() {
        useHardware = true;
        cameraContainer.setVisibility(View.GONE);
        scanModePlaceholder.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
        scanFrameOverlay.setVisibility(View.GONE);
        setPlaceholderActive(true);

        scanStatusCard.setBackgroundResource(R.drawable.bg_scan_status_active);
        scanStatusIconTv.setText(R.string.manual_scan_active_icon);
        scanStatusTextTv.setText(R.string.manual_scan_active);
        scanStatusTextTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        scanStatusTipTv.setText(R.string.manual_scan_active_tip);
        startScanBtn.setVisibility(View.GONE);
        stopScanBtn.setVisibility(View.VISIBLE);

        if (hardwareController == null) {
            hardwareController = new BarcodeHardwareController();
        }
        hardwareController.startScan(new BarcodeHardwareController.Callback() {
            @Override
            public void onBarcodeDetected(@NonNull String rawCode) {
                if (!isAdded()) return;
                vm.onBarcodeScanned(rawCode);
            }

            @Override
            public void onStartFailed(@NonNull String reason) {
                Log.w(TAG, "[ManualScanFragment] hardware scan failed: " + reason);
                if (!isAdded()) return;
                // 中途失败 (硬件掉线等) — 这次 session 后续直接走 CameraX, 不再尝试硬件
                Snackbar.make(requireView(),
                        R.string.manual_scan_camera_init_failed,
                        Snackbar.LENGTH_LONG).show();
                stopScan();
            }
        });
    }

    /** CameraX 路径: 先检查权限, 没有就弹系统授权. */
    private void startCameraWithPermissionCheck() {
        useHardware = false;
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Log.i(TAG, "[ManualScanFragment] CAMERA not granted, requesting");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /** 相机权限被永久拒绝后, 跳系统「应用详情」页, 让用户手动开启权限. */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // 极少数 ROM 无该系统页面 — 静默降级, 不崩.
            Log.w(TAG, "[ManualScanFragment] no app-details settings activity", e);
        }
    }

    private void startCamera() {
        cameraContainer.setVisibility(View.VISIBLE);
        scanModePlaceholder.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        scanFrameOverlay.setVisibility(View.VISIBLE);

        scanStatusCard.setBackgroundResource(R.drawable.bg_scan_status_active);
        scanStatusIconTv.setText(R.string.manual_scan_active_icon);
        scanStatusTextTv.setText(R.string.manual_scan_active);
        scanStatusTextTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        scanStatusTipTv.setText(R.string.manual_scan_active_tip);
        startScanBtn.setVisibility(View.GONE);
        stopScanBtn.setVisibility(View.VISIBLE);

        // startScan 现在异步返回 void, 错误通过 Callback.onStartFailed 异步通知.
        // 守卫 getView()/isAdded() 因为回调可能在 Fragment 已 detach 时到达.
        barcodeController.startScan(this, previewView, new BarcodeCameraController.Callback() {
            @Override
            public void onBarcodeDetected(@NonNull String rawCode) {
                vm.onBarcodeScanned(rawCode);
            }

            @Override
            public void onStartFailed(@NonNull String reason) {
                Log.w(TAG, "[ManualScanFragment] camera start failed: " + reason);
                if (!isAdded()) return;
                View v = getView();
                if (v != null) {
                    Snackbar.make(v, R.string.manual_scan_camera_init_failed,
                            Snackbar.LENGTH_LONG).show();
                }
                forceIdleState();
            }
        });
    }

    /** 切换占位面板 idle/active 文案. */
    private void setPlaceholderActive(boolean active) {
        if (active) {
            placeholderTitleTv.setText(R.string.manual_scan_hardware_active_title);
            placeholderTipTv.setText(R.string.manual_scan_hardware_active_tip);
        } else {
            placeholderTitleTv.setText(R.string.manual_scan_hardware_idle_title);
            placeholderTipTv.setText(R.string.manual_scan_hardware_idle_tip);
        }
    }

    private void stopScan() {
        Log.i(TAG, "[ManualScanFragment] stopScan() pressed, useHardware=" + useHardware);
        if (useHardware && hardwareController != null) {
            hardwareController.stopScan();
        } else if (!useHardware && barcodeController != null) {
            barcodeController.stopScan();
        }
        forceIdleState();
        // 重置 cameraContainer: 硬件模式恢复占位, CameraX 模式隐藏 preview
        scanModePlaceholder.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
        scanFrameOverlay.setVisibility(View.GONE);
        setPlaceholderActive(false);
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
        if (hardwareController != null) {
            hardwareController.release();
            hardwareController = null;
        }
        if (barcodeController != null) {
            barcodeController.release();
            barcodeController = null;
        }
        beepPlayer.release();
        // 清空 history row 缓存 — View parent (historyList) 已 detach, 缓存里
        // 的 view 不再使用, 否则下次 onViewCreated 重建 historyList 后缓存里的
        // row 还指向旧 parent, addView 会抛 IllegalStateException.
        historyRowPool.clear();
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
            // 用户原始输入已经是 display 形式 (16/17 位), 24 位归一化形式要剥前导 0
            // 跟列表 / 详情卡片显示口径保持一致 — 跟 ExtraResultAdapter 同一种坑.
            String rawEpc = r.normalizedEpc == null
                    ? (inputEt.getText() == null ? "" : inputEt.getText().toString())
                    : EpcNormalizer.toDisplayForm(r.normalizedEpc);
            String msg = getString(R.string.manual_err_not_found, rawEpc);
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
        boolean shouldBeep = false;
        switch (e.outcome) {
            case SCANNED_FOUND:
                msg = getString(R.string.manual_scan_done_found,
                        display == null ? "" : display);
                shouldBeep = true;
                break;
            case SCANNED_EXTRA:
                msg = getString(R.string.manual_scan_done_extra,
                        display == null ? "" : display);
                shouldBeep = true;
                break;
            case SCANNED_DUPLICATE:
                String at = e.previousAt == null ? "—" : DateUtil.formatTimestamp(e.previousAt);
                msg = getString(R.string.manual_scan_done_dup,
                        display == null ? "" : display, at);
                shouldBeep = true;
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
        if (shouldBeep) beepPlayer.play();
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show();
    }

    /**
     * 缓存上次渲染过的 row view — 按 ScanRecord.id 复用同一个 View 实例,
     * 避免每次 refreshHistory 重新 inflate + 4 次 findViewById. onDestroyView
     * 清空以防父 LinearLayout detach 后 view 仍 hold 住 fragment.
     *
     * <p>{@code historyList.removeAllViews()} 在每次 render 前仍调, 然后只把
     * 缓存里命中的 row 重新 addView 进来 — 比每次 inflate 节省 ~10 倍时间,
     * 解决 ManualScanFragment resume → refreshHistory 高频触发的可见 jank.</p>
     */
    private final java.util.HashMap<Long, View> historyRowPool = new java.util.HashMap<>();

    private void renderHistory(@Nullable List<ScanRecord> records) {
        historyList.removeAllViews();
        if (records == null || records.isEmpty()) {
            historyEmptyTv.setVisibility(View.VISIBLE);
            // 清空缓存 — 空列表意味着当前不再需要 row; 否则下次进来用户翻历史会
            // 撞到 detached LinearLayout 的 view 残留. 清空让 GC 回收.
            historyRowPool.clear();
            return;
        }
        historyEmptyTv.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (ScanRecord r : records) {
            View row = historyRowPool.get(r.id);
            if (row == null) {
                row = inflater.inflate(R.layout.view_manual_history_row, historyList, false);
                historyRowPool.put(r.id, row);
            }
            bindHistoryRow(row, r);
            historyList.addView(row);
        }
    }

    private void bindHistoryRow(View row, ScanRecord r) {
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
        detailNameTv.setText(a.assetModel == null || a.assetModel.isEmpty()
                ? getString(R.string.manual_asset_status_unknown) : a.assetModel);
        detailOrgTv.setText(a.dept == null || a.dept.isEmpty()
                ? getString(R.string.manual_asset_status_unknown) : a.dept);
        detailStatusTv.setText(a.custodian == null || a.custodian.isEmpty()
                ? getString(R.string.manual_asset_status_unknown) : a.custodian);
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