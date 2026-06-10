package com.huaxia.inventory.ui.scan;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import java.util.ArrayList;
import java.util.List;

public class RecentScanAdapter extends RecyclerView.Adapter<RecentScanAdapter.VH> {

    private final List<ScanRecord> items = new ArrayList<>();

    public void prepend(ScanRecord r) {
        items.add(0, r);
        notifyItemInserted(0);
    }

    public void setAll(List<ScanRecord> rs) {
        items.clear();
        items.addAll(rs);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_scan, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ScanRecord r = items.get(position);
        h.time.setText(DateUtil.formatTimestamp(r.scannedAt));
        h.epc.setText(r.epc);
        h.rssi.setText(r.rssi == null ? "" : r.rssi);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView time, epc, rssi;
        VH(View v) {
            super(v);
            time = v.findViewById(R.id.timeTv);
            epc = v.findViewById(R.id.epcTv);
            rssi = v.findViewById(R.id.rssiTv);
        }
    }
}
