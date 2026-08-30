package com.kadensaas.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 架電結果コードのマスタ。
 *
 * <p>★ KPI の分母・分子の定義をこの boolean 列に閉じ込めてある。
 * 集計 SQL がリテラル文字列に依存すると、コードを 1 つ足すたびに
 * 全クエリを探して回ることになり、Spring Boot 側と FastAPI 側で
 * 「接続率」が違う値になる。
 */
@Entity
@Table(name = "disposition_codes")
@Getter
@Setter
public class DispositionCode {

    @Id
    private String code;

    private String label;

    @Column(name = "is_connected")
    private boolean connected;

    @Column(name = "is_conversation")
    private boolean conversation;

    @Column(name = "is_success")
    private boolean success;

    /** ★ true を選んだら二度とかけない。関門が参照する。 */
    @Column(name = "is_dnc")
    private boolean dnc;

    @Column(name = "excluded_from_denominator")
    private boolean excludedFromDenominator;

    @Column(name = "sort_order")
    private int sortOrder;
}
