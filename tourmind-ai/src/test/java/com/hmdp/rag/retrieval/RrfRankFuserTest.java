package com.hmdp.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RrfRankFuser 单元测试 — 纯逻辑类，直接实例化测试。
 */
@DisplayName("RRF 排名融合器")
class RrfRankFuserTest {

    private RrfRankFuser fuser;

    @BeforeEach
    void setUp() {
        fuser = new RrfRankFuser(60); // 标准 k=60
    }

    @Test
    @DisplayName("两路交集 — 两路都出现的文档获得更高 RRF 分数")
    void intersectionGetsHigherScore() {
        List<RrfRankFuser.ScoredDoc> es = List.of(
                doc("1:0", "1", "text1"),
                doc("2:0", "2", "text2"),
                doc("3:0", "3", "text3")
        );
        List<RrfRankFuser.ScoredDoc> vec = List.of(
                doc("1:1", "1", "text1"),  // spotId=1 在两路都出现
                doc("4:0", "4", "text4")
        );

        List<RrfRankFuser.FusedResult> results = fuser.fuse(es, vec, 10);

        // spotId=1 应排第一（两路都有贡献）
        assertEquals("1", results.get(0).getSpotId());
        // spot1 RRF = 1/(60+1) + 1/(60+1) = 2/61 ≈ 0.0328
        double expected = 1.0 / 61.0 + 1.0 / 61.0;
        assertEquals(expected, results.get(0).getRrfScore(), 0.0001);
    }

    @Test
    @DisplayName("排名靠前获得更高 RRF 分数")
    void higherRankGetsHigherScore() {
        // doc A rank 1, doc B rank 2
        List<RrfRankFuser.ScoredDoc> es = List.of(
                doc("a", "A", "textA"),
                doc("b", "B", "textB")
        );
        List<RrfRankFuser.ScoredDoc> vec = List.of(
                doc("b2", "B", "textB")  // spotId=B 在第二路也出现，rank 1
        );

        List<RrfRankFuser.FusedResult> results = fuser.fuse(es, vec, 10);

        // B 排第一（两路都有贡献：ES rank 2 + vec rank 1）
        // A 排第二（仅 ES rank 1）
        assertEquals("B", results.get(0).getSpotId(),
                "B在两路都出现，RRF分数应最高");
        assertEquals("A", results.get(1).getSpotId(),
                "A仅在一路出现，RRF分数次之");
    }

    @Test
    @DisplayName("topK 限制返回数量")
    void topKLimit() {
        List<RrfRankFuser.ScoredDoc> es = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            es.add(doc(i + ":0", String.valueOf(i), "text" + i));
        }

        List<RrfRankFuser.FusedResult> results = fuser.fuse(es, Collections.emptyList(), 3);
        assertEquals(3, results.size());
    }

    @Test
    @DisplayName("空输入返回空")
    void emptyInput() {
        List<RrfRankFuser.FusedResult> results = fuser.fuse(
                Collections.emptyList(), Collections.emptyList(), 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("单路结果也可正确排序")
    void singleListWorks() {
        List<RrfRankFuser.ScoredDoc> vec = List.of(
                doc("3:0", "3", "text3"),
                doc("1:0", "1", "text1"),
                doc("2:0", "2", "text2")
        );

        List<RrfRankFuser.FusedResult> results = fuser.fuse(Collections.emptyList(), vec, 10);

        assertEquals(3, results.size());
        assertEquals("3", results.get(0).getSpotId()); // rank 1
        assertEquals("1", results.get(1).getSpotId()); // rank 2
        assertEquals("2", results.get(2).getSpotId()); // rank 3
    }

    @Test
    @DisplayName("文本和元数据正确传递")
    void textAndMetadataPreserved() {
        Map<String, Object> meta = Map.of("spotId", "5", "spotName", "灵隐寺");
        List<RrfRankFuser.ScoredDoc> es = List.of(
                new RrfRankFuser.ScoredDoc("5:0", "5", 0.95, "灵隐寺位于飞来峰", meta)
        );

        List<RrfRankFuser.FusedResult> results = fuser.fuse(es, Collections.emptyList(), 10);

        assertEquals(1, results.size());
        assertEquals("灵隐寺位于飞来峰", results.get(0).getText());
        assertEquals("5", results.get(0).getMetadata().get("spotId"));
    }

    @Test
    @DisplayName("自定义 rankConstant 生效")
    void customRankConstant() {
        RrfRankFuser customFuser = new RrfRankFuser(0); // k=0

        List<RrfRankFuser.ScoredDoc> es = List.of(
                doc("1:0", "1", "text1"),
                doc("2:0", "2", "text2")
        );

        List<RrfRankFuser.FusedResult> results = customFuser.fuse(es, Collections.emptyList(), 10);

        // k=0: rank1 = 1/1 = 1.0, rank2 = 1/2 = 0.5
        assertEquals(1.0, results.get(0).getRrfScore(), 0.001);
        assertEquals(0.5, results.get(1).getRrfScore(), 0.001);
    }

    private RrfRankFuser.ScoredDoc doc(String docId, String spotId, String text) {
        return new RrfRankFuser.ScoredDoc(docId, spotId, 1.0, text,
                Map.of("spotId", spotId));
    }
}
