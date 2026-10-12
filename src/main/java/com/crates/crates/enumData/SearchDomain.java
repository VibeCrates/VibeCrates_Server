package com.crates.crates.enumData;

import com.crates.crates.entity.contents.Book;
import com.crates.crates.entity.contents.Movie;
import com.crates.crates.entity.contents.Music;

import java.util.Locale;
import java.util.Optional;

/**
 * 검색어가 가리키는 콘텐츠 종류. AI 서버가 검색어를 임베딩할 때 함께 판단해 준다("잔잔한 영화" → movie).
 *
 * <p>"영화·노래·책" 같은 작품 종류 명사가 검색어에 있을 때만 그 종류이고, 아니면 ALL이다. 장르(재즈·발라드)는
 * 단서로 보지 않는다(AI 서버 계약). 검색 보드는 ALL이면 종류를 섞고(board-mix), 한 종류면 그 종류로만 채운다.</p>
 */
public enum SearchDomain {

    ALL(null),
    BOOK(Book.DTYPE),
    MOVIE(Movie.DTYPE),
    MUSIC(Music.DTYPE);

    /** 콘텐츠 dtype(Qdrant payload의 dtype과 같다). ALL은 없다. */
    private final String dtype;

    SearchDomain(String dtype)
    {
        this.dtype = dtype;
    }

    public Optional<String> dtype()
    {
        return Optional.ofNullable(dtype);
    }

    /**
     * AI 서버가 보내는 값(all, book, movie, music)을 바꾼다. 대소문자는 가리지 않는다.
     *
     * @return 모르는 값이거나 null이면 빈 값
     */
    public static Optional<SearchDomain> fromAiValue(String value)
    {
        if (value == null)
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        }
        catch (IllegalArgumentException e)
        {
            return Optional.empty();
        }
    }
}
