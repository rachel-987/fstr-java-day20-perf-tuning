package fstr_java.day20.perf_tuning.service;
import fstr_java.day20.perf_tuning.dto.AuthorResponse;
import fstr_java.day20.perf_tuning.model.Author;
import fstr_java.day20.perf_tuning.repository.AuthorRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthorService {

    private final AuthorRepository authorRepository;


    @Transactional(readOnly = true)
    public List<AuthorResponse> getAllAuthors() {

        List<Author> authors =
                authorRepository.findAllWithBooks();

        return authors.stream()
                .map(author -> new AuthorResponse(
                        author.getId(),
                        author.getName(),
                        author.getBooks().size()
                ))
                .toList();
    }

}