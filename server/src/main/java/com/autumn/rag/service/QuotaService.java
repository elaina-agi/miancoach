package com.autumn.rag.service;

import com.autumn.rag.common.TooManyRequestsException;
import com.autumn.rag.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 调大模型的接口(问答 / 批改 / 总结)按天限次,给每天的花费封顶。
 * 和 nginx 分工:nginx 每个 IP 每分钟 5 次,防短时间狂刷;这里管一天总共能用多少。
 *
 * <p>游客合用一个全站额度,不按人头给:游客只能按 IP 区分,换个 IP 就是"新游客",按人头给等于没上限;
 * 合用一个的话,被刷光也只花掉这么多,而且不影响登录用户。
 * 登录用户按账号各自计数——注册要过邮箱验证码,批量刷号成本高,按账号限是靠得住的。
 *
 * <p>计数放内存,按服务器时区的自然日归零。单机部署够用,重启清零可以接受。
 */
@Service
public class QuotaService {

    private final int guestDaily;
    private final int userDaily;
    private final Clock clock;

    private LocalDate day;
    private int guestUsed;
    private final Map<UUID, Integer> userUsed = new HashMap<>();

    /** 有两个构造器时 Spring 不知道用哪个,@Autowired 指定这一个。 */
    @Autowired
    public QuotaService(AppProperties props) {
        this(props.quota().guestDaily(), props.quota().userDaily(), Clock.systemDefaultZone());
    }

    /** 包内可见,单测直接 new,传一个能拨动的时钟测跨天归零。 */
    QuotaService(int guestDaily, int userDaily, Clock clock) {
        this.guestDaily = guestDaily;
        this.userDaily = userDaily;
        this.clock = clock;
    }

    /**
     * 记一次调用,在真正调模型之前调。今天的额度已经用完就抛 429(这次不计数)。
     * userId 为 null = 游客。
     */
    public synchronized void consume(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            guestUsed = 0;
            userUsed.clear();
        }
        if (userId == null) {
            if (guestDaily > 0 && guestUsed >= guestDaily) {
                throw new TooManyRequestsException("今天的游客额度已经用完了,登录后可以继续使用");
            }
            guestUsed++;
            return;
        }
        int used = userUsed.getOrDefault(userId, 0);
        if (userDaily > 0 && used >= userDaily) {
            throw new TooManyRequestsException("今天已经用满 " + userDaily + " 次了,明天再来吧");
        }
        userUsed.put(userId, used + 1);
    }
}
