# BÁO CÁO PHÂN TÍCH & TỐI ƯU HIỆU NĂNG

> **Dự án:** `perf-tuning`  
> **Công nghệ:** Java · Spring Boot · Spring Data JPA / Hibernate · PostgreSQL 16  
> **Phạm vi:** N+1 Query và Missing Database Index  
> **Phương pháp:** Hibernate SQL Log · `EXPLAIN (ANALYZE, BUFFERS)` · HTTP benchmark bằng `curl`

---

## 1. Tóm tắt kết quả

Bài thực hành đã phát hiện và xử lý **hai vấn đề hiệu năng tiềm ẩn** trong ứng dụng Spring Boot. Việc kiểm chứng được thực hiện trên database PostgreSQL local bằng log SQL và số đo thực tế.

| Hạng mục | Trước tối ưu | Sau tối ưu | Kết quả quan sát |
|:--|--:|--:|:--|
| **N+1 Query** — thời gian phản hồi API trung bình | **76,275 ms** | **11,395 ms** | **Giảm 85,06%** · nhanh hơn ~6,69 lần |
| **Missing Index** — thời gian thực thi SQL | **8,617 ms** | **2,730 ms** | **Giảm 68,32%** · nhanh hơn ~3,16 lần |

**Phạm vi đo:**

- N+1: **100 Author, 1.000 Book**; mỗi phiên bản warm-up 5 request và đo 10 request.
- Index: **201.000 Book**; so sánh truy vấn cùng điều kiện lọc, có và không sử dụng index (mỗi phương án 1 lần đo).
- Đây là kết quả **trên môi trường local**, không phải cam kết hiệu năng production.

---

## 2. Vấn đề #1 — N+1 Query

### 2.1. Hiện tượng và nguyên nhân

API `GET /api/authors` trả về danh sách Author cùng số lượng Book của mỗi Author.

**Code trước tối ưu (`AuthorService`):**

```java
List<Author> authors = authorRepository.findAll();

return authors.stream()
        .map(author -> new AuthorResponse(
                author.getId(),
                author.getName(),
                author.getBooks().size()
        ))
        .toList();
```

Quan hệ `Author.books` được cấu hình `FetchType.LAZY`. Khi Service gọi `author.getBooks().size()` trên từng Author, Hibernate phải khởi tạo collection Book chưa được tải. Điều này làm phát sinh các câu SQL truy vấn Book lặp lại.

**Luồng trước tối ưu:**

```text
GET /api/authors
      │
      ▼
authorRepository.findAll()          → 1 SELECT author
      │
      ▼
Duyệt 100 Author
      ├── Author 1: getBooks().size() → SELECT book WHERE author_id = ?
      ├── Author 2: getBooks().size() → SELECT book WHERE author_id = ?
      ├── Author 3: getBooks().size() → SELECT book WHERE author_id = ?
      └── ... lặp lại cho các Author còn lại
```

Với 100 Author, **101 SQL queries** là số lượng *dự kiến* trong điều kiện mỗi Author phát sinh một truy vấn riêng, không có batch fetching hoặc cache can thiệp. **Chưa thực hiện đếm đầy đủ SQL của một request** nên không coi 101 là số đo thực tế.

### 2.2. Cách xác định — Hibernate SQL Log

Bật log SQL và gọi `GET /api/authors`. Log trước khi sửa cho thấy:

**Truy vấn lấy Author:**

```sql
SELECT a1_0.id, a1_0.name
FROM author a1_0;
```

**Truy vấn Book lặp lại theo Author:**

```sql
SELECT b1_0.author_id, b1_0.id,
       b1_0.publication_year, b1_0.title
FROM book b1_0
WHERE b1_0.author_id = ?;
```

Đoạn log được thu thập có **1 truy vấn Author và 4 truy vấn Book lặp lại**; đây là bằng chứng trực tiếp về kiểu truy cập N+1, nhưng chưa phải toàn bộ log request.

### 2.3. Giải pháp đã áp dụng — `LEFT JOIN FETCH`

**`AuthorRepository.java`:**

```java
@Query("""
    SELECT DISTINCT a
    FROM Author a
    LEFT JOIN FETCH a.books
    """)
List<Author> findAllWithBooks();
```

**`AuthorService.java`:**

```java
List<Author> authors = authorRepository.findAllWithBooks();
```

`LEFT JOIN FETCH` tải Author và collection Book trong cùng truy vấn. `LEFT JOIN` giữ cả Author không có Book; `DISTINCT` là cách viết đã dùng trong bài (Hibernate 6 cũng tự loại trùng entity gốc khi fetch join collection).

**Luồng sau tối ưu:**

```text
GET /api/authors
      │
      ▼
authorRepository.findAllWithBooks()
      │
      ▼
1 SELECT ... FROM author LEFT JOIN book
      │
      ▼
Hibernate ánh xạ Author + Book đã được tải
      │
      ▼
Tạo AuthorResponse (không cần SELECT Book lặp lại)
```

**SQL log sau khi sửa:**

```sql
SELECT DISTINCT
    a1_0.id, b1_0.author_id, b1_0.id,
    b1_0.publication_year, b1_0.title, a1_0.name
FROM author a1_0
LEFT JOIN book b1_0 ON a1_0.id = b1_0.author_id;
```

Log sau sửa cho thấy **một truy vấn JOIN** trong phần được cung cấp, thay cho chuỗi truy vấn Book lặp lại trước đó.

### 2.4. Benchmark HTTP — trước và sau tối ưu

**Điều kiện:** 100 Author, 1.000 Book; endpoint `GET http://localhost:8080/api/authors`; restart ứng dụng khi đổi phiên bản; warm-up 5 request; đo 10 request bằng `curl.exe -w "%{time_total}"`.

| Lần đo | Trước: N+1 (ms) | Sau: JOIN FETCH (ms) |
|--:|--:|--:|
| 1 | 107,770 | 12,898 |
| 2 | 77,146 | 13,831 |
| 3 | 82,678 | 12,660 |
| 4 | 84,690 | 12,167 |
| 5 | 73,740 | 11,370 |
| 6 | 72,431 | 10,854 |
| 7 | 64,778 | 11,222 |
| 8 | 63,307 | 9,333 |
| 9 | 69,171 | 10,405 |
| 10 | 67,043 | 9,214 |
| **Trung bình** | **76,275** | **11,395** |
| **Trung vị** | **73,086** | **11,296** |
| **Nhanh nhất** | **63,307** | **9,214** |
| **Chậm nhất** | **107,770** | **13,831** |

**Biểu diễn tương đối theo thời gian trung bình (thanh dài hơn = chậm hơn):**

```text
N+1         ██████████████████████████████  76,275 ms
JOIN FETCH  ████▌                           11,395 ms
```

**Kết quả:**

- Giảm trung bình **64,880 ms/request**.
- Giảm khoảng **85,06% thời gian phản hồi**.
- Tốc độ phản hồi trong phép thử nhanh hơn khoảng **6,69 lần**.

> **Giới hạn:** `curl time_total` đo thời gian HTTP nhìn từ client (bao gồm overhead localhost), không chỉ thời gian SQL. Kết quả chịu ảnh hưởng bởi JIT, cache, GC, logging và môi trường chạy. Số lượng SQL `101 → 1` vẫn là dự kiến, chưa được thống kê toàn bộ request.

---

## 3. Vấn đề #2 — Thiếu Index trên `book.publication_year`

### 3.1. Hiện tượng và cách phát hiện

Truy vấn lọc Book theo năm xuất bản:

```sql
SELECT * FROM book WHERE publication_year = 2010;
```

Kiểm tra các index đang có:

```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'book';
```

**Kết quả ban đầu:**

```text
book_pkey | CREATE UNIQUE INDEX book_pkey
            ON public.book USING btree (id)
```

Bảng chỉ có index trên khóa chính `id`, **chưa có index cho `publication_year`**. Đây là nguy cơ khi dữ liệu lớn; không thể kết luận query chậm chỉ từ việc thiếu index.

**Đo trên 1.000 Book trước khi tạo index:**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM book WHERE publication_year = 2010;
```

```text
Seq Scan on book  (cost=0.00..20.50 rows=100 width=30)
  (actual time=0.008..0.056 rows=100 loops=1)
  Filter: (publication_year = 2010)
  Rows Removed by Filter: 900
  Buffers: shared hit=8
Planning Time: 0.262 ms
Execution Time: 0.086 ms
```

PostgreSQL chọn **Sequential Scan**. Với 1.000 Book, truy vấn chỉ mất **0,086 ms**, nên chưa có bằng chứng về vấn đề chậm thực tế ở quy mô này.

### 3.2. Giải pháp đã áp dụng

Tạo B-tree index và cập nhật thống kê:

```sql
CREATE INDEX idx_book_publication_year
ON book (publication_year);

ANALYZE book;
```

Sau khi tạo index, PostgreSQL sử dụng **Bitmap Index Scan + Bitmap Heap Scan**. Trên 1.000 Book, thời gian đo là **0,090 ms**, so với **0,086 ms** trước đó: không có cải thiện đáng kể ở bảng nhỏ.

### 3.3. Kiểm chứng trên dữ liệu lớn

Thêm 200.000 Book để tổng số bản ghi đạt **201.000**:

```sql
INSERT INTO book (title, publication_year, author_id)
SELECT
    'Performance Book ' || gs,
    2000 + (gs % 25),
    1 + (gs % 100)
FROM generate_series(1, 200000) AS gs;

ANALYZE book;
```

Kiểm thử cùng điều kiện `publication_year = 2024` (trả về **8.000 Book**).

**Phương án A — Cho phép PostgreSQL dùng index:**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM book WHERE publication_year = 2024;
```

```text
Bitmap Heap Scan on book
  (actual time=0.443..2.520 rows=8000 loops=1)
  Recheck Cond: (publication_year = 2024)
  Heap Blocks: exact=1870
  Buffers: shared hit=1878
  -> Bitmap Index Scan on idx_book_publication_year
       (actual time=0.260..0.261 rows=8000 loops=1)
       Index Cond: (publication_year = 2024)
       Buffers: shared hit=8
Planning Time: 0.150 ms
Execution Time: 2.730 ms
```

**Phương án B — Tạm tắt các kiểu index scan trong transaction:**

```sql
BEGIN;
SET LOCAL enable_indexscan = off;
SET LOCAL enable_bitmapscan = off;
SET LOCAL enable_indexonlyscan = off;

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM book WHERE publication_year = 2024;

ROLLBACK;
```

```text
Seq Scan on book
  (actual time=0.097..8.367 rows=8000 loops=1)
  Filter: (publication_year = 2024)
  Rows Removed by Filter: 193000
  Buffers: shared hit=1877
Planning Time: 0.083 ms
Execution Time: 8.617 ms
```

### 3.4. Bảng so sánh hiệu năng

| Chỉ số | Không sử dụng Index | Sử dụng Index |
|:--|--:|--:|
| Tổng số Book | 201.000 | 201.000 |
| Điều kiện | `publication_year = 2024` | Như nhau |
| Số dòng trả về | 8.000 | 8.000 |
| Execution Plan | Seq Scan | Bitmap Index + Bitmap Heap Scan |
| Execution Time | **8,617 ms** | **2,730 ms** |
| Rows Removed by Filter | 193.000 | Không có chỉ số tương ứng |
| Shared buffer hits | 1.877 | 1.878 (heap + index) |

**Biểu diễn tương đối theo thời gian thực thi (thanh dài hơn = chậm hơn):**

```text
Seq Scan      ██████████████████████████████  8,617 ms
Dùng Index    █████████▌                      2,730 ms
```

**Kết quả quan sát:**

- Giảm **5,887 ms** trong phép đo.
- Giảm khoảng **68,32% thời gian thực thi**.
- Phương án dùng index nhanh hơn khoảng **3,16 lần** trong phép thử.

> **Giới hạn:** Mỗi phương án được đo **một lần**. Trường hợp không dùng index được tạo bằng cấu hình planner tạm thời, không phải xóa index. Kết quả có thể chịu ảnh hưởng cache và không đại diện cho mọi dữ liệu hoặc workload production.

---

## 4. Đối chiếu yêu cầu bài tập

| Yêu cầu | Trạng thái | Bằng chứng trong báo cáo |
|:--|:--:|:--|
| Xác định ít nhất 2 điểm nghẽn tiềm ẩn | **Đạt** | N+1 Query (mục 2), Missing Index (mục 3) |
| Sửa lỗi N+1 | **Đạt** | `LEFT JOIN FETCH` và SQL log sau sửa (mục 2.3) |
| Sửa lỗi thiếu index | **Đạt trong DB local** | `CREATE INDEX`, Bitmap Index Scan (mục 3.2–3.3) |
| Ghi lại vấn đề đã tìm thấy | **Đạt** | Mục 2.1 và 3.1 |
| Ghi cách xác định | **Đạt** | Hibernate SQL Log, `pg_indexes`, `EXPLAIN ANALYZE` |
| Ghi giải pháp áp dụng | **Đạt** | Code Java/JPQL và SQL tạo index |
| **Nâng cao:** So sánh thời gian trước/sau | **Đạt** | 10 lần đo HTTP mỗi phiên bản; benchmark SQL có/không index |

## 5. Kết luận

Bài thực hành đã **phát hiện và xử lý hai vấn đề hiệu năng** theo yêu cầu:

1. **N+1 Query:** chuyển từ truy cập lazy collection theo từng Author sang `LEFT JOIN FETCH`. Thời gian HTTP trung bình giảm từ **76,275 ms** xuống **11,395 ms** trong phép thử 10 request mỗi phiên bản.
2. **Missing Index:** tạo `idx_book_publication_year`. Trên bộ dữ liệu 201.000 Book, truy vấn có sử dụng index mất **2,730 ms**, so với **8,617 ms** khi buộc thực hiện Seq Scan.

**Kết luận về yêu cầu:** Đã đáp ứng **đầy đủ yêu cầu bắt buộc** và **phần nâng cao so sánh thời gian** theo số liệu thử nghiệm đã thu thập.

*Báo cáo được tổng hợp từ code, SQL log và kết quả benchmark thực tế của bài thực hành; các số liệu suy luận được phân biệt với số liệu đã đo.*
