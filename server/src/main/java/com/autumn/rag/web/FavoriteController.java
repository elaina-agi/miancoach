package com.autumn.rag.web;

import com.autumn.rag.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 用户身份只来自 JWT,任何请求参数中的 userId 都不参与查询。 */
@Tag(name = "题目收藏", description = "收藏知识库整道题,按当前登录用户隔离")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {
    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) {
        this.favorites = favorites;
    }

    @Operation(summary = "我的收藏", description = "按板块筛选,按收藏时间倒序分页;page 从 1 开始,size 最大 100")
    @GetMapping
    public FavoriteService.Page list(@CurrentUser LoginUser user,
                                     @RequestParam(required = false) String group,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return favorites.list(user.id(), group, page, size);
    }

    @Operation(summary = "当前用户是否已收藏这道题")
    @GetMapping("/{chunkId}")
    public FavoriteService.State state(@CurrentUser LoginUser user, @PathVariable long chunkId) {
        return favorites.state(user.id(), chunkId);
    }

    @Operation(summary = "收藏一道题", description = "幂等,不会因重复点击改变收藏时间")
    @PutMapping("/{chunkId}")
    public FavoriteService.State add(@CurrentUser LoginUser user, @PathVariable long chunkId) {
        return favorites.add(user.id(), chunkId);
    }

    @Operation(summary = "取消收藏", description = "幂等,只能取消自己的收藏")
    @DeleteMapping("/{chunkId}")
    public FavoriteService.State remove(@CurrentUser LoginUser user, @PathVariable long chunkId) {
        return favorites.remove(user.id(), chunkId);
    }
}
