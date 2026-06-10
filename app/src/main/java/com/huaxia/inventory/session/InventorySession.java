package com.huaxia.inventory.session;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class InventorySession {

    private final MutableLiveData<Long> currentTaskId = new MutableLiveData<>();

    @Nullable
    public Long getCurrentTaskId() {
        return currentTaskId.getValue();
    }

    public LiveData<Long> currentTaskId() {
        return currentTaskId;
    }

    public void setCurrentTaskId(long id) {
        currentTaskId.postValue(id);
    }

    public void clear() {
        currentTaskId.postValue(null);
    }
}
