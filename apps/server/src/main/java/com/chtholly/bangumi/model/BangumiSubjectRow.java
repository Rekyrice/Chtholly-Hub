package com.chtholly.bangumi.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
public class BangumiSubjectRow {
    private Long id;
    private Integer type;
    private String name;
    private String nameCn;
    private String summary;
    private Boolean nsfw;
    private LocalDate airDate;
    private BigDecimal score;
    private Integer rank;
    private Integer epsCount;
    private String rawJson;
    private Instant syncedAt;
}
