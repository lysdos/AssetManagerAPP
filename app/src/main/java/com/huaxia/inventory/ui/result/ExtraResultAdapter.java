package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import java.util.List;

public class ExtraResultAdapter extends RecyclerView.Adapter<ExtraResultAdapter.VH> {

    public interface OnExtraClickListener {
        void onExtraClick(ExtraWithClaim data);
    }

    private final List<ExtraWithClaim> items;
    private final OnExtraClickListener listener;

    public ExtraResultAdapter(List<ExtraWithClaim> items, OnExtraClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_extra_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ExtraWithClaim data = items.get(position);
        ScanRecord r = data.scan;
        h.epc.setText(r.epc == null ? "" : r.epc);
        h.time.setText(DateUtil.formatTimestamp(r.scannedAt));
        h.rssi.setText(r.rssi == null ? "" : r.rssi + " dBm");

        if (data.isClaimed()) {
            h.claimedTag.setVisibility(View.VISIBLE);
            h.claimedTag.setText(R.string.extra_tag_claimed);
            h.claimInfo.setVisibility(View.VISIBLE);
            h.claimInfo.setText(formatClaimInfo(h, data.claim));
        } else {
            h.claimedTag.setVisibility(View.GONE);
            h.claimInfo.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onExtraClick(data);
        });
    }

    private String formatClaimInfo(VH h, ExtraClaim c) {
        StringBuilder sb = new StringBuilder();
        sb.append(h.itemView.getContext().getString(R.string.extra_claim_info_fmt,
                nullSafe(c.dept), nullSafe(c.custodian)));
        if (c.assetType != null && !c.assetType.isEmpty()) {
            sb.append(" · ").append(c.assetType);
        }
        if (c.assetModel != null && !c.assetModel.isEmpty()) {
            sb.append(" / ").append(c.assetModel);
        }
        if (c.serialNumber != null && !c.serialNumber.isEmpty()) {
            sb.append(" · SN:").append(c.serialNumber);
        }
        if (c.epc != null && !c.epc.isEmpty()) {
            sb.append(" · EPC:").append(c.epc);
        }
        return sb.toString();
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView epc, time, rssi, claimedTag, claimInfo;
        VH(View v) {
            super(v);
            epc = v.findViewById(R.id.extraEpcTv);
            time = v.findViewById(R.id.extraTimeTv);
            rssi = v.findViewById(R.id.extraRssiTv);
            claimedTag = v.findViewById(R.id.extraClaimedTag);
            claimInfo = v.findViewById(R.id.extraClaimInfoTv);
        }
    }
}