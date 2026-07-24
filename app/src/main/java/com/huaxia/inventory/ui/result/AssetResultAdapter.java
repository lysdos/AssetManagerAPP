package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.EpcNormalizer;
import java.util.List;
import java.util.Set;

public class AssetResultAdapter extends RecyclerView.Adapter<AssetResultAdapter.VH> {

    public interface OnAssetClickListener {
        void onAssetClicked(Asset asset);
    }

    private List<Asset> items;
    private Set<Long> matchedIds;
    @Nullable
    private final OnAssetClickListener clickListener;

    public AssetResultAdapter(List<Asset> items, Set<Long> matchedIds) {
        this(items, matchedIds, null);
    }

    public AssetResultAdapter(List<Asset> items, Set<Long> matchedIds,
                               @Nullable OnAssetClickListener clickListener) {
        this.items = items;
        this.matchedIds = matchedIds;
        this.clickListener = clickListener;
    }

    /**
     * 替换数据集合, 不替换 adapter 实例 — 让 ResultFragment 多个 LiveData observer
     * 触发时只更新数据, 不反复 new AssetResultAdapter + setAdapter,
     * 避免 RecyclerView 内部短链残留. 与 setMatchedIds 配对使用,
     * 单次刷新一般两个 setter 都调, 末尾调一次 notifyDataSetChanged.
     */
    public void setItems(List<Asset> items) {
        this.items = items;
    }

    public void setMatchedIds(Set<Long> matchedIds) {
        this.matchedIds = matchedIds;
    }

    public List<Asset> getItems() { return items; }
    public Set<Long> getMatchedIds() { return matchedIds; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_asset_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Asset a = items.get(position);
        h.code.setText(a.epc == null ? "" : EpcNormalizer.toDisplayForm(a.epc));
        h.serial.setText(a.serialNumber == null ? "" : a.serialNumber);
        h.custodian.setText(a.custodian == null ? "" : a.custodian);
        h.dept.setText(a.dept == null ? "" : a.dept);

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

        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onAssetClicked(a);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView code, serial, custodian, dept, pill;
        VH(View v) {
            super(v);
            code = v.findViewById(R.id.assetCodeTv);
            serial = v.findViewById(R.id.assetSerialTv);
            custodian = v.findViewById(R.id.assetCustodianTv);
            dept = v.findViewById(R.id.assetDeptTv);
            pill = v.findViewById(R.id.assetStatusPill);
        }
    }
}