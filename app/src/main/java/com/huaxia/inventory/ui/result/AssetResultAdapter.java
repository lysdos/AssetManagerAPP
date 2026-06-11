package com.huaxia.inventory.ui.result;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.StatusDerivation;
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
                .inflate(R.layout.item_asset_result, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.title.setText("#" + a.excelRowIndex + "  " +
                (a.assetName == null ? "" : a.assetName) + " / " +
                (a.assetModel == null ? "" : a.assetModel));
        h.org.setText("所属: " + (a.org == null ? "" : a.org));
        h.location.setText("地点: " + (a.detailedLocation == null ? "" : a.detailedLocation));
        h.epc.setText(a.epc == null ? "" : a.epc);
        boolean matched = matchedIds.contains(a.id);
        StatusDerivation.Status s = StatusDerivation.classify(matched, false);
        h.status.setText(label(s));
        h.status.setBackgroundColor(color(s));
    }

    @Override public int getItemCount() { return items.size(); }

    private String label(StatusDerivation.Status s) {
        switch (s) {
            case FOUND:   return "已盘";
            case MISSING: return "盘亏";
            default:      return "";
        }
    }

    private int color(StatusDerivation.Status s) {
        switch (s) {
            case FOUND:   return Color.parseColor("#4CAF50");
            case MISSING: return Color.parseColor("#F44336");
            default:      return Color.GRAY;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, org, location, epc, status;
        VH(View v) {
            super(v);
            title = v.findViewById(R.id.titleTv);
            org = v.findViewById(R.id.orgTv);
            location = v.findViewById(R.id.locationTv);
            epc = v.findViewById(R.id.epcTv);
            status = v.findViewById(R.id.statusTv);
        }
    }
}
