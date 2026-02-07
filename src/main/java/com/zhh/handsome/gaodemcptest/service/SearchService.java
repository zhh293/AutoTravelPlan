package com.zhh.handsome.gaodemcptest.service;

import com.zhh.handsome.gaodemcptest.model.PagedResult;
import com.zhh.handsome.gaodemcptest.model.SuggestItem;

public interface SearchService {
    public PagedResult<SuggestItem> suggest(String keyword, String city, String types, int page, int size);
}
