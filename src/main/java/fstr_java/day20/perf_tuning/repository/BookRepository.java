package fstr_java.day20.perf_tuning.repository;

import fstr_java.day20.perf_tuning.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository
        extends JpaRepository<Book, Long> {
}