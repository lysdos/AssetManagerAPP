package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import java.util.List;
import java.util.Set;

public class AssetResultAdapter extends RecyclerView.Adapter<AssetResultAdapter.VH> {

    private final List<Asset> items;
    private final Set<Long> matchedIds;

    public AssetResultAdapter(List<Asset> items, Set<Long> matchedIds) {
        this.items = items;
        this.matchedIds = matchedIds;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_asset_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.code.setText(a.epc == null ? "" : a.epc);
        h.name.setText(a.assetName == null ? "" : a.assetName);
        h.org.setText(a.org == null ? "" : a.org);

        boolean matched = matchedIds.contains(a.id);
        if (matched) {
            h.pill.setText(R.string.tag_found);
            h.pill.setBackgroundResource(R.drawable.bg_tag_green);
            h.pill.setTextColor(androidx.core.content.ContextCompat.getColor(
                    h.itemView.getContext(), R.color.tagGreenFg));
        } else {
            h.pill.setText(R.string.tag_missing);
            h.pill.setBackgroundResource(R.drawable.bg_tag_red);
            h.pill.setTextColor(androidx.core.content.ContextCompat.getColor(
                    h.itemView.getContext(), R.color.tagRedFg));
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView code, name, org, pill;
        VH(View v) {
            super(v);
            code = v.findViewById(R.id.assetCodeTv);
            name = v.findViewById(R.id.assetNameTv);
            org = v.findViewById(R.id.assetOrgTv);
            pill = v.findViewById(R.id.assetStatusPill);
        }
    }
}
