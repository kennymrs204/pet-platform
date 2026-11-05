package com.petplatform.boarding.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 新增每日护理记录（ADR-015 §5 补记 2026-10-27：写闸=该单主责 ∨ 持 boarding:care:create 的本店员工代录）。
 * feeding/watering/walk 为结构化 DTO（服务端序列化为 JSON 列），
 * 拒绝任意原始 JSON 串；photos 仅对象存储 key/URL。careDate 空=业务今日，不可未来、须在入住区间内。
 */
@Data
public class CareRecordRequest {

    private LocalDate careDate;

    @Valid
    private Feeding feeding;

    @Valid
    private Watering watering;

    @Valid
    private Walk walk;

    @Size(max = 500, message = "观察记录不超过500字")
    private String observation;

    @Size(max = 20, message = "照片最多20张")
    private List<@Size(max = 255, message = "照片地址不超过255字符") String> photos;

    private Boolean abnormalFlag;

    @Size(max = 500, message = "异常描述不超过500字")
    private String abnormalDesc;

    /** 喂食：次数 + 食量描述。 */
    @Data
    public static class Feeding {
        @Min(value = 0, message = "次数不能为负")
        private Integer count;
        @Size(max = 50, message = "食量描述不超过50字")
        private String amountText;
    }

    /** 饮水：次数。 */
    @Data
    public static class Watering {
        @Min(value = 0, message = "次数不能为负")
        private Integer count;
    }

    /** 遛弯：次数 + 总时长（分钟）。 */
    @Data
    public static class Walk {
        @Min(value = 0, message = "次数不能为负")
        private Integer count;
        @Min(value = 0, message = "时长不能为负")
        private Integer durationMin;
    }
}
