package fstr_java.day20.perf_tuning.dto;

public record AuthorResponse(
        Long id,
        String name,
        int bookCount
) {}