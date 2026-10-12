package com.crates.crates.enumData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchDomainTest {

    @Test
    void AI_서버_값을_대소문자_없이_바꾼다()
    {
        assertThat(SearchDomain.fromAiValue("movie")).contains(SearchDomain.MOVIE);
        assertThat(SearchDomain.fromAiValue(" Book ")).contains(SearchDomain.BOOK);
        assertThat(SearchDomain.fromAiValue("all")).contains(SearchDomain.ALL);
    }

    @Test
    void 모르는_값이나_null은_빈_값()
    {
        assertThat(SearchDomain.fromAiValue("podcast")).isEmpty();
        assertThat(SearchDomain.fromAiValue(null)).isEmpty();
    }

    @Test
    void ALL만_dtype이_없다()
    {
        assertThat(SearchDomain.ALL.dtype()).isEmpty();
        assertThat(SearchDomain.MOVIE.dtype()).contains("MOVIE");
    }
}
