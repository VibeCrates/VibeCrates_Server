package com.crates.crates.service;

import com.crates.crates.enumData.SearchDomain;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BoardContentMixTest {

    private final BoardContentMix mix = new BoardContentMix(4, 2, 2);

    @Test
    void ALL이면_board_mix대로_섞는다()
    {
        assertThat(mix.perBoard(SearchDomain.ALL)).isEqualTo(Map.of("BOOK", 4, "MUSIC", 2, "MOVIE", 2));
    }

    @Test
    void 한_종류면_8칸을_모두_그_종류로()
    {
        assertThat(mix.perBoard(SearchDomain.MOVIE)).isEqualTo(Map.of("MOVIE", 8));
        assertThat(mix.perBoard(SearchDomain.BOOK)).isEqualTo(Map.of("BOOK", 8));
        assertThat(mix.perBoard(SearchDomain.MUSIC)).isEqualTo(Map.of("MUSIC", 8));
    }
}
