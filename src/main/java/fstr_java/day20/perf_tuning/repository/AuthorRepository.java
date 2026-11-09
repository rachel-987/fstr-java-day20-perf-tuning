package fstr_java.day20.perf_tuning.repository;

import fstr_java.day20.perf_tuning.model.Author;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository
        extends JpaRepository<Author, Long> {

    @Query("""
        SELECT DISTINCT a
        FROM Author a
        LEFT JOIN FETCH a.books
        """)
    List<Author> findAllWithBooks();
}