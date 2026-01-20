package com.zhh.handsome.gaodemcptest.controller;


import com.zhh.handsome.gaodemcptest.model.PagedResult;
import com.zhh.handsome.gaodemcptest.model.SuggestItem;
import com.zhh.handsome.gaodemcptest.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    @Autowired
    private SearchService searchService;

    @GetMapping("/suggest")
    public PagedResult<SuggestItem> suggest(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "types", required = false) String types,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        if (size > 50) size = 50;
        return searchService.suggest(keyword, city, types, page, size);
    }
}
