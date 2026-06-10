package com.huaxia.inventory.ui.import_;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;

public class AssetPreviewAdapter extends RecyclerView.Adapter<AssetPreviewAdapter.VH> {

    private final List<Asset> items;

    public AssetPreviewAdapter(List<Asset> items) { this.items = items; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_asset_preview, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.index.setText(String.valueOf(position + 1));
        h.name.setText(a.assetName);
        h.model.setText(a.assetModel == null ? "" : a.assetModel);
        h.org.setText(a.org == null ? "" : a.org);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView index, name, model, org;
        VH(View v) {
            super(v);
            index = v.findViewById(R.id.indexTv);
            name = v.findViewById(R.id.assetNameTv);
            model = v.findViewById(R.id.assetModelTv);
            org = v.findViewById(R.id.orgTv);
        }
    }
}
