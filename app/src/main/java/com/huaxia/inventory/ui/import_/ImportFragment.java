package com.huaxia.inventory.ui.import_;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;

public class ImportFragment extends Fragment {

    private ImportViewModel vm;
    private MaterialButton pickBtn, startBtn;
    private androidx.cardview.widget.CardView fileInfoCard;
    private TextView fileNameTv, countTv, errorsTv, previewHeaderTv;
    private RecyclerView previewRv;
    private Uri pickedUri;
    private String pickedFileName;
    private java.util.List<com.huaxia.inventory.data.entity.Asset> currentAssets;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pickedUri = uri;
                requireContext().getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                pickedFileName = queryFileName(uri);
                vm.parseFile(uri, pickedFileName);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        vm = new ViewModelProvider(this).get(ImportViewModel.class);
        pickBtn = v.findViewById(R.id.pickFileBtn);
        startBtn = v.findViewById(R.id.startBtn);
        fileInfoCard = v.findViewById(R.id.fileInfoCard);
        fileNameTv = v.findViewById(R.id.fileNameTv);
        countTv = v.findViewById(R.id.countTv);
        errorsTv = v.findViewById(R.id.errorsTv);
        previewHeaderTv = v.findViewById(R.id.previewHeaderTv);
        previewRv = v.findViewById(R.id.previewRv);
        previewRv.setLayoutManager(new LinearLayoutManager(requireContext()));

        pickBtn.setOnClickListener(btn -> filePicker.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}));

        startBtn.setOnClickListener(btn -> {
            if (currentAssets == null || currentAssets.isEmpty()) return;
            String fileName = pickedFileName;
            vm.commitToTask(fileName, currentAssets, taskId -> {
                ((InventoryApp) requireActivity().getApplication()).session.setCurrentTaskId(taskId);
                requireActivity().runOnUiThread(() -> {
                    com.google.android.material.bottomnavigation.BottomNavigationView nav =
                            requireActivity().findViewById(R.id.bottomNav);
                    nav.setSelectedItemId(R.id.nav_scan);
                });
            });
        });

        vm.parsedFile().observe(getViewLifecycleOwner(), pf -> {
            if (pf == null) return;
            fileInfoCard.setVisibility(View.VISIBLE);
            previewHeaderTv.setVisibility(View.VISIBLE);
            previewRv.setVisibility(View.VISIBLE);
            fileNameTv.setText(getString(R.string.import_file_label) + ": " + pf.fileName);
            countTv.setText(getString(R.string.import_count_label) + ": " + pf.successCount);
            if (pf.errors.isEmpty()) {
                errorsTv.setVisibility(View.GONE);
            } else {
                errorsTv.setVisibility(View.VISIBLE);
                errorsTv.setText(getString(R.string.import_errors_label) + ":\n"
                        + String.join("\n", pf.errors));
            }
            currentAssets = vm.lastParsedAssets;
            previewRv.setAdapter(new AssetPreviewAdapter(pf.preview));
            startBtn.setEnabled(pf.successCount > 0);
        });
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        }
        return uri.getLastPathSegment();
    }
}
