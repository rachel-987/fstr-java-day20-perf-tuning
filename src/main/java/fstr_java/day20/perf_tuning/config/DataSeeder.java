package fstr_java.day20.perf_tuning.config;

import fstr_java.day20.perf_tuning.model.Author;
import fstr_java.day20.perf_tuning.model.Book;
import fstr_java.day20.perf_tuning.repository.AuthorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class DataSeeder {

    private final AuthorRepository authorRepository;

    @Bean
    CommandLineRunner seedData() {
        return args -> {
            if (authorRepository.count() > 0) {
                return;
            }

            for (int i = 1; i <= 100; i++) {
                Author author = new Author();
                author.setName("Author " + i);

                List<Book> books = new ArrayList<>();

                for (int j = 1; j <= 10; j++) {
                    Book book = new Book();
                    book.setTitle("Book " + i + "-" + j);
                    book.setPublicationYear(
                            2000 + (j % 20)
                    );
                    book.setAuthor(author);
                    books.add(book);
                }

                author.setBooks(books);
                authorRepository.save(author);
            }

            System.out.println("Seed completed!");
        };
    }
}