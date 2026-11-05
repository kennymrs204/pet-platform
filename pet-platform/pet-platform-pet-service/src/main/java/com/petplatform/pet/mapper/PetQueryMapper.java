package com.petplatform.pet.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 宠物档案只读投影（不建实体，纯读 pet_pet）。
 */
@Mapper
public interface PetQueryMapper {

    @Select("""
            <script>
            SELECT p.id, p.name, p.species, p.breed, p.gender, p.avatar,
                   p.status, p.weight_kg, p.size_category, p.birthday
            FROM pet_pet p
            WHERE p.deleted = 0 AND p.id IN
            <foreach collection="petIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            </script>
            """)
    List<PetRow> selectSummaries(@Param("petIds") Collection<Long> petIds);

    /** 历史业务引用回填名字：不过滤 deleted（口径见 PetQueryApi#namesIncludingDeleted）。 */
    @Select("""
            <script>
            SELECT p.id, p.name
            FROM pet_pet p
            WHERE p.id IN
            <foreach collection="petIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            </script>
            """)
    List<PetRow> selectNamesIncludingDeleted(@Param("petIds") Collection<Long> petIds);

    /**
     * 宠物名包含匹配取 id，**keyset 游标**（{@code id > afterId} + {@code ORDER BY id ASC}）：
     * 每批最多 limit 行，调用方循环到"不满一批"为止 → 结果集完整，limit 只是单批行数上限。
     * {@code kw} 必须是**已转义**的字面量（{@code LikePattern.literal}），配合 {@code ESCAPE '!'}
     * 让 % 与 _ 只当普通字符；排序取 id 保证跨批不重不漏。
     */
    @Select("""
            <script>
            SELECT p.id FROM pet_pet p
            WHERE p.deleted = 0 AND p.name LIKE CONCAT('%', #{kw}, '%') ESCAPE '!'
            <if test="afterId != null"> AND p.id &gt; #{afterId} </if>
            ORDER BY p.id ASC LIMIT #{limit}
            </script>
            """)
    List<Long> selectIdsByNameLike(@Param("kw") String kw, @Param("afterId") Long afterId,
                                   @Param("limit") int limit);

    /** 只读投影（map-underscore-to-camel 自动映射）。 */
    class PetRow {
        private Long id;
        private String name;
        private String species;
        private String breed;
        private String gender;
        private String avatar;
        private String status;
        private java.math.BigDecimal weightKg;
        private String sizeCategory;
        private java.time.LocalDate birthday;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSpecies() { return species; }
        public void setSpecies(String species) { this.species = species; }
        public String getBreed() { return breed; }
        public void setBreed(String breed) { this.breed = breed; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public java.math.BigDecimal getWeightKg() { return weightKg; }
        public void setWeightKg(java.math.BigDecimal weightKg) { this.weightKg = weightKg; }
        public String getSizeCategory() { return sizeCategory; }
        public void setSizeCategory(String sizeCategory) { this.sizeCategory = sizeCategory; }
        public java.time.LocalDate getBirthday() { return birthday; }
        public void setBirthday(java.time.LocalDate birthday) { this.birthday = birthday; }
    }
}
