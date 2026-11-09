package fstr_java.day20.perf_tuning.controller;

import fstr_java.day20.perf_tuning.dto.AuthorResponse;
import fstr_java.day20.perf_tuning.service.AuthorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/authors")
@RequiredArgsConstructor
public class AuthorController {

    private final AuthorService authorService;

    @GetMapping
    public List<AuthorResponse> getAllAuthors() {
        return authorService.getAllAuthors();
    }
}