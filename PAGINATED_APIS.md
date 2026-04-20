# Paginated API Reference (FE Integration Guide)

**Base URL**: `http://localhost:8080/ebook`

---

## Pagination Contract

### Query Parameters

| Param  | Type    | Default | Description                                          |
|--------|---------|---------|------------------------------------------------------|
| `page` | Integer | `0`     | Zero-based page index                                |
| `size` | Integer | `20`    | Items per page (max: `100`)                          |
| `sort` | String  | varies  | Format: `field,direction` (e.g. `title,asc`)         |

> If `page` and `size` are both omitted, most endpoints return the **full unbounded list** (legacy mode).
> Payment endpoints always enforce pagination (default `page=0, size=20`).

### Response Envelope

Every API response is wrapped in:

```json
{
  "success": true,
  "message": "...",
  "data": { ... }
}
```

When paginated, `data` contains:

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

When unpaginated (no page/size params), `data` is a plain array: `[ ... ]`

---

## 1. Books - Public List

```
GET /ebook/books?page=0&size=10&sort=createdAt,desc
```

- **Auth**: Public (no token)
- **Sortable fields**: `createdAt`, `title`, `price`, `publishedDate`
- **Default sort**: `createdAt,desc`
- **Response item** (`BookResponse`):

```json
{
  "id": "uuid",
  "title": "string",
  "description": "string",
  "price": 299.00,
  "discount": 50.00,
  "keywords": "string",
  "publishedDate": "2026-04-10T10:00:00Z",
  "pages": 320,
  "coverUrl": "/ebook/files/covers/uuid.jpg",
  "previewUrl": "/ebook/files/previews/uuid.png",
  "bookUrl": null,
  "versionNumber": "1.0",
  "isPublished": true,
  "status": "APPROVED",
  "rejectionReason": null,
  "categoryId": "uuid",
  "categoryName": "Technology",
  "authorId": "uuid",
  "authorName": "John Doe",
  "createdAt": "2026-04-10T10:00:00Z",
  "updatedAt": "2026-04-10T10:00:00Z"
}
```

---

## 2. Books - By Category

```
GET /ebook/books/category/{categoryId}?page=0&size=10&sort=title,asc
```

- **Auth**: Public
- **Sortable fields**: `createdAt`, `title`, `price`, `publishedDate`
- **Default sort**: `createdAt,desc`
- **Response item**: Same `BookResponse` as above

---

## 3. Books - By Author

```
GET /ebook/books/author/{authorId}?page=0&size=10&sort=title,asc
```

- **Auth**: Public
- **Sortable fields**: `createdAt`, `title`, `price`, `publishedDate`
- **Default sort**: `createdAt,desc`
- **Response item**: Same `BookResponse` as above

---

## 4. Books - My Books (Author's own books)

```
GET /ebook/books/my?page=0&size=10&sort=createdAt,desc
```

- **Auth**: `USER` or `ADMIN` (Bearer token required)
- **Sortable fields**: `createdAt`, `title`, `price`, `publishedDate`
- **Default sort**: `createdAt,desc`
- **Response item**: Same `BookResponse` (includes `status`, `rejectionReason` for author dashboard)

---

## 5. Admin - All Books

```
GET /ebook/admin/books?page=0&size=10&sort=createdAt,desc
```

- **Auth**: `ADMIN` only
- **Sortable fields**: `createdAt`, `title`, `price`, `publishedDate`
- **Default sort**: `createdAt,desc`
- **Response item**: Same `BookResponse` (all statuses: PENDING, APPROVED, REJECTED)

---

## 6. Admin - Pending Books

```
GET /ebook/admin/books/pending?page=0&size=10&sort=createdAt,desc
```

- **Auth**: `ADMIN` only
- **Sortable fields**: `createdAt`, `title`, `price`, `publishedDate`
- **Default sort**: `createdAt,desc`
- **Response item**: Same `BookResponse` (only `status=PENDING`)

---

## 7. Authors - Public List

```
GET /ebook/authors?page=0&size=10&sort=firstName,asc
```

- **Auth**: Public
- **Sortable fields**: `createdAt`, `firstName`, `lastName`
- **Default sort**: `createdAt,desc`
- **Response item** (`AuthorResponse`):

```json
{
  "id": "uuid",
  "email": "author@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "phone": "9876543210",
  "designation": "Senior Developer",
  "description": "Bio text...",
  "qualification": "PhD in CS",
  "profileUrl": "/ebook/files/profile/uuid.jpg",
  "emailVerified": true,
  "isActive": true,
  "status": "ACTIVE",
  "createdAt": "2026-04-10T10:00:00Z",
  "updatedAt": "2026-04-10T10:00:00Z"
}
```

> Public endpoint returns only **active + verified** authors.

---

## 8. Admin - All Authors

```
GET /ebook/admin/authors?page=0&size=10&sort=createdAt,desc
```

- **Auth**: `ADMIN` only
- **Sortable fields**: `createdAt`, `firstName`, `lastName`
- **Default sort**: `createdAt,desc`
- **Response item**: Same `AuthorResponse` (includes inactive authors)

---

## 9. Categories - Public List

```
GET /ebook/categories?page=0&size=10&sort=name,asc
```

- **Auth**: Public
- **Sortable fields**: `name`, `createdAt`
- **Default sort**: `name,desc`
- **Response item** (`CategoryResponse`):

```json
{
  "id": "uuid",
  "name": "Technology",
  "slug": "technology",
  "description": "Tech books",
  "isActive": true,
  "createdAt": "2026-04-10T10:00:00Z",
  "updatedAt": "2026-04-10T10:00:00Z"
}
```

> Public endpoint returns only **active** categories.

---

## 10. Admin - All Categories

```
GET /ebook/categories/admin/all?page=0&size=10&sort=name,asc
```

- **Auth**: `ADMIN` only
- **Sortable fields**: `name`, `createdAt`
- **Default sort**: `name,desc`
- **Response item**: Same `CategoryResponse` (includes inactive)

---

## 11. My Payment History

```
GET /ebook/payments/my?page=0&size=10&sort=createdAt,desc
```

- **Auth**: `USER` or `ADMIN` (Bearer token required)
- **Sortable fields**: `createdAt`, `amount`, `status`
- **Default sort**: `createdAt,desc`
- **Always paginated** (defaults to `page=0, size=20` even without params)
- **Response item** (`PaymentResponse`):

```json
{
  "paymentId": "uuid",
  "userId": "uuid",
  "userEmail": "user@example.com",
  "amount": 249.00,
  "currency": "INR",
  "paymentMethod": "MOCK",
  "status": "SUCCESS",
  "rejectionReason": null,
  "createdAt": "2026-04-10T10:00:00Z",
  "approvedAt": "2026-04-10T10:00:00Z",
  "rejectedAt": null,
  "items": [
    {
      "bookId": "uuid",
      "title": "Book Title",
      "coverUrl": "/ebook/files/covers/uuid.jpg",
      "authorName": "John Doe",
      "price": 299.00,
      "discount": 50.00,
      "effectivePrice": 249.00
    }
  ]
}
```

---

## 12. My Purchased Books (Library)

```
GET /ebook/payments/my/books?page=0&size=10&sort=accessGrantedAt,desc
```

- **Auth**: `USER` or `ADMIN` (Bearer token required)
- **Sortable fields**: `accessGrantedAt`, `lastReadAt`
- **Default sort**: `accessGrantedAt,desc`
- **Always paginated** (defaults to `page=0, size=20` even without params)
- **Response item** (`PurchasedBookResponse`):

```json
{
  "bookId": "uuid",
  "title": "Book Title",
  "description": "...",
  "coverUrl": "/ebook/files/covers/uuid.jpg",
  "bookUrl": "/ebook/files/books/uuid.pdf",
  "authorName": "John Doe",
  "authorId": "uuid",
  "categoryName": "Technology",
  "categoryId": "uuid",
  "pricePaid": 299.00,
  "discount": 50.00,
  "accessGrantedAt": "2026-04-10T10:00:00Z",
  "lastReadAt": "2026-04-12T15:30:00Z",
  "progressPercentage": 45.5
}
```

---

## FE Integration Notes

### Generic Fetch Helper

```ts
const BASE_URL = "http://localhost:8080/ebook";

interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

async function fetchPaged<T>(
  endpoint: string,
  page = 0,
  size = 10,
  sort?: string,
  token?: string
): Promise<ApiResponse<PagedResponse<T>>> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (sort) params.set("sort", sort);

  const headers: Record<string, string> = {};
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`${BASE_URL}${endpoint}?${params}`, { headers });
  return res.json();
}
```

### Usage Examples

```ts
// Public books
const books = await fetchPaged<BookResponse>("/books", 0, 10, "title,asc");

// Books by category
const catBooks = await fetchPaged<BookResponse>("/books/category/cat-uuid", 0, 10);

// Admin: all authors
const authors = await fetchPaged<AuthorResponse>("/admin/authors", 0, 10, "firstName,asc", adminToken);

// My purchased books
const library = await fetchPaged<PurchasedBookResponse>("/payments/my/books", 0, 10, "accessGrantedAt,desc", userToken);
```

### Resolving File URLs

All `coverUrl`, `previewUrl`, `profileUrl`, `bookUrl` fields are **relative paths** like `/ebook/files/covers/uuid.jpg`. Prepend the server base:

```ts
const fileUrl = (path: string | null) => path ? `http://localhost:8080${path}` : null;

// In JSX:
<img src={fileUrl(book.coverUrl)} alt={book.title} />
```
