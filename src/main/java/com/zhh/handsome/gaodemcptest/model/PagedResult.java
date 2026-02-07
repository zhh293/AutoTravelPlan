package com.zhh.handsome.gaodemcptest.model;

import java.io.Serializable;
import java.util.List;

public class PagedResult<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private int page;
    private int size;
    private long total;
    private boolean fromCache;
    private List<T> items;

    public int getPage() {
        return page;
    }
    public void setPage(int page) {
        this.page = page;
    }
    public int getSize() {
        return size;
    }
    public void setSize(int size) {
        this.size = size;
    }
    public long getTotal() {
        return total;
    }
    public void setTotal(long total) {
        this.total = total;
    }
    public boolean isFromCache() {
        return fromCache;
    }
    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }
    public List<T> getItems() {
        return items;
    }
    public void setItems(List<T> items) {
        this.items = items;
    }
}
