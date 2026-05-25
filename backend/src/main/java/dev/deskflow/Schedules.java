package dev.deskflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import org.springframework.scheduling.support.CronExpression;

final class Schedules {
    static Instant next(JsonNode config,Instant now){
        if(!config.path("mode").asText("interval").equals("cron"))return now.plusSeconds(config.path("intervalMinutes").asLong()*60);
        var cron=CronExpression.parse(config.path("cron").asText());
        var next=cron.next(now.atZone(ZoneId.of(config.path("timezone").asText("UTC"))));
        if(next==null)throw new IllegalArgumentException("Cron has no next occurrence");
        var following=cron.next(next);
        if(following!=null&&Duration.between(next,following).getSeconds()<60)throw new IllegalArgumentException("Schedules must be at least one minute apart");
        return next.toInstant();
    }
}
