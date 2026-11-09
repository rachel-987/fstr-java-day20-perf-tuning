# Hướng dẫn chạy và kiểm thử Performance Tuning

## 1. Khởi động PostgreSQL

Khởi động Docker container:

```bash
docker start product-postgres
```

Nếu chưa có container, tạo mới:

```bash
docker run -d --name product-postgres -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app123 -e POSTGRES_DB=perfdb -p 5433:5432 postgres:16
```

Kết nối database:

```bash
docker exec -it product-postgres psql -U app -d perfdb
```

## 2. Khởi động Spring Boot

Kiểm tra cấu hình trong `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/perfdb
    username: app
    password: app123
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        generate_statistics: true

logging:
  level:
    org.hibernate.SQL: DEBUG
```

Chạy ứng dụng bằng IntelliJ hoặc Maven:

```bash
./mvnw spring-boot:run
```

Trên Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

## 3. Kiểm tra dữ liệu

Trong PostgreSQL:

```sql
SELECT COUNT(*) FROM author;
SELECT COUNT(*) FROM book;
```

Dữ liệu ban đầu mong đợi:

```text
author = 100
book   = 1000
```

Nếu database mới chưa có dữ liệu, khởi động ứng dụng để `DataSeeder` tạo dữ liệu mẫu.

## 4. Kiểm thử API

Gọi API:

```bash
curl http://localhost:8080/api/authors
```

Kết quả mong đợi: 100 Author, mỗi Author có `bookCount = 10`.

## 5. Kiểm thử N+1 Query

### 5.1. Trước tối ưu

Trong `AuthorService.java`, sử dụng:

```java
List<Author> authors = authorRepository.findAll();
```

Giữ nguyên logic:

```java
author.getBooks().size()
```

Restart Spring Boot và gọi:

```bash
curl http://localhost:8080/api/authors
```

Kiểm tra Hibernate SQL log. Dự kiến xuất hiện:

- 1 query lấy danh sách Author.
- 100 query lấy Book theo từng Author nếu không có batch fetching hoặc cache can thiệp.

### 5.2. Đo thời gian trước tối ưu

Mở PowerShell:

```powershell
# Warm-up 5 lần
1..5 | ForEach-Object {
    curl.exe -s -o NUL http://localhost:8080/api/authors
}

# Đo 10 lần
1..10 | ForEach-Object {
    curl.exe -s -o NUL `
        -w "Time: %{time_total}s`n" `
        http://localhost:8080/api/authors
}
```

Lưu lại 10 kết quả.

### 5.3. Sau tối ưu

Trong `AuthorRepository.java`:

```java
@Query("""
    SELECT DISTINCT a
    FROM Author a
    LEFT JOIN FETCH a.books
    """)
List<Author> findAllWithBooks();
```

Trong `AuthorService.java`, thay bằng:

```java
List<Author> authors =
        authorRepository.findAllWithBooks();
```

Restart Spring Boot.

Gọi API và kiểm tra Hibernate SQL log, dự kiến chỉ còn một câu SQL JOIN để lấy Author và Book.

Chạy lại script PowerShell ở mục 5.2 để đo thời gian sau tối ưu.

### 5.4. Kết quả benchmark đã đo

| Chỉ số | Trước tối ưu | Sau tối ưu |
|---|---:|---:|
| Average | 76.275 ms | 11.395 ms |
| Median | 73.086 ms | 11.296 ms |
| Min | 63.307 ms | 9.214 ms |
| Max | 107.770 ms | 13.831 ms |

## 6. Kiểm thử Missing Index

### 6.1. Kiểm tra index

Trong PostgreSQL:

```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'book';
```

### 6.2. Kiểm tra Execution Plan

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM book
WHERE publication_year = 2010;
```

Quan sát `Seq Scan`, `Index Scan` hoặc `Bitmap Heap Scan` và `Execution Time`.

### 6.3. Tạo Index

Nếu index chưa tồn tại:

```sql
CREATE INDEX IF NOT EXISTS idx_book_publication_year
ON book (publication_year);

ANALYZE book;
```

Chạy lại `EXPLAIN (ANALYZE, BUFFERS)` để kiểm tra PostgreSQL có sử dụng index hay không.

## 7. Benchmark Index với dữ liệu lớn

### 7.1. Thêm dữ liệu thử nghiệm

Chỉ chạy khi bảng `book` có 1.000 bản ghi ban đầu và Author có ID từ 1 đến 100.

```sql
INSERT INTO book (title, publication_year, author_id)
SELECT
    'Performance Book ' || gs,
    2000 + (gs % 25),
    1 + (gs % 100)
FROM generate_series(1, 200000) AS gs;

ANALYZE book;
```

Kiểm tra:

```sql
SELECT COUNT(*) FROM book;
```

Kết quả mong đợi: `201000`.

### 7.2. Đo khi sử dụng Index

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM book
WHERE publication_year = 2024;
```

### 7.3. Đo khi không sử dụng Index

```sql
BEGIN;

SET LOCAL enable_indexscan = off;
SET LOCAL enable_bitmapscan = off;
SET LOCAL enable_indexonlyscan = off;

EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM book
WHERE publication_year = 2024;

ROLLBACK;
```

### 7.4. Kết quả benchmark đã đo

| Chỉ số | Không dùng Index | Có Index |
|---|---|---|
| Execution Plan | Seq Scan | Bitmap Heap Scan |
| Execution Time | 8.617 ms | 2.730 ms |
| Rows Returned | 8.000 | 8.000 |

Lưu ý: Thời gian có thể khác nhau tùy môi trường, cache và số lần chạy.

## 8. Dọn dẹp dữ liệu benchmark

Kiểm tra số bản ghi thử nghiệm:

```sql
SELECT COUNT(*)
FROM book
WHERE title LIKE 'Performance Book %';
```

Nếu đúng 200.000 bản ghi vừa tạo trong môi trường LAB:

```sql
DELETE FROM book
WHERE title LIKE 'Performance Book %';

ANALYZE book;
```

Kiểm tra lại:

```sql
SELECT COUNT(*) FROM author;
SELECT COUNT(*) FROM book;
```

Kết quả mong đợi:

```text
author = 100
book   = 1000
```

## 9. Kiểm tra cuối cùng

- [ ] API `GET /api/authors` trả về dữ liệu chính xác.
- [ ] `AuthorService` sử dụng `findAllWithBooks()` sau khi kiểm thử.
- [ ] Hibernate SQL log cho thấy JOIN FETCH.
- [ ] Index `idx_book_publication_year` tồn tại.
- [ ] Database đã được dọn dẹp về 100 Author và 1.000 Book.
- [ ] Kết quả benchmark được đối chiếu với `REPORT.md`.

