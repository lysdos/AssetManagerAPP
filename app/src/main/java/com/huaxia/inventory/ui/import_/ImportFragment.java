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
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.huaxia.inventory.InventoryApp;
import com.huaxia.inventory.R;

public class ImportFragment extends Fragment {

    private ImportViewModel vm;
    private MaterialButton startBtn;
    private View uploadArea, fileInfoRow;
    private TextView fileNameTv, clearBtn;
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
        startBtn = v.findViewById(R.id.startBtn);
        uploadArea = v.findViewById(R.id.uploadArea);
        fileInfoRow = v.findViewById(R.id.fileInfoRow);
        fileNameTv = v.findViewById(R.id.fileNameTv);
        clearBtn = v.findViewById(R.id.clearBtn);

        uploadArea.setOnClickListener(view -> filePicker.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}));
        clearBtn.setOnClickListener(view -> clearSelection());

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
            fileInfoRow.setVisibility(View.VISIBLE);
            fileNameTv.setText(getString(R.string.import_file_selected, pf.fileName));
            currentAssets = vm.lastParsedAssets;
            startBtn.setEnabled(pf.successCount > 0);
        });

        vm.parseError().observe(getViewLifecycleOwner(), err -> {
            if (err == null) return;
            Toast.makeText(requireContext(),
                    getString(R.string.err_parse_failed, err),
                    Toast.LENGTH_LONG).show();
        });
    }

    private void clearSelection() {
        pickedUri = null;
        pickedFileName = null;
        currentAssets = null;
        fileInfoRow.setVisibility(View.GONE);
        startBtn.setEnabled(false);
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
