# ebookHub Backend — API Documentation

**Base URL:** `http://localhost:8080/ebook`
**Content-Type:** `application/json`
**Auth scheme:** `Authorization: Bearer <JWT>` for protected routes.

Designed for integration with a **Next.js (App Router) SEO + SSR + RSC** frontend. See [Next.js Integration Guide](#nextjs-integration-guide).

---

## Table of Contents

1. [Response Formats](#response-formats)
2. [Auth Model & Token Strategy](#auth-model--token-strategy)
3. [API Surface — Quick Reference](#api-surface--quick-reference)
4. [Auth APIs](#auth-apis)
5. [User Profile APIs](#user-profile-apis)
6. [Category APIs](#category-apis)
7. [Author APIs](#author-apis)
8. [Book APIs](#book-apis)
9. [Cart & Checkout APIs](#cart--checkout-apis)
10. [Config APIs](#config-apis)
11. [Admin APIs](#admin-apis)
12. [Internal / Well-Known](#internal--well-known)
13. [Error Codes Reference](#error-codes-reference)
14. [Enums Reference](#enums-reference)
15. [Next.js Integration Guide](#nextjs-integration-guide)

---

## Response Formats

### Success Response

```json
{
  "success": true,
  "message": "Human-readable success message",
  "data": { }
}
```

> `data` is `null` for operations that don't return a payload (logout, password change, delete, etc.).

### Error Response

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Human-readable error description"
}
```

### Validation Error Response

```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "email: Email must be valid; password: Password must be at least 8 characters long"
}
```

---

## Auth Model & Token Strategy

- **Access token** — short-lived JWT (default `900s` / 15 min), RS256-signed. Decode claims on the client to read `sub` (user UUID), `groups` (roles), `userType`, `sessionId`.
- **Refresh token** — opaque string tied to `deviceFingerprint`. Rotated on every `/auth/refresh` call. Old refresh token is immediately revoked.
- **Device fingerprint** — Required for `login`, `refresh`, `logout`. Generate a stable per-browser string (e.g. FingerprintJS, or a hashed combo of `user-agent + screen + timezone`). Must match across login→refresh→logout.
- **JWT claims:**
  ```json
  {
    "iss": "ebookhub-auth",
    "sub": "<user-uuid>",
    "upn": "<user-uuid>",
    "groups": ["USER"],            // or ["USER","ADMIN"]
    "userType": "READER",          // or "AUTHOR"
    "sessionId": "<session-uuid>",
    "exp": 1712000000
  }
  ```
- **Account lockout** — 5 failed logins → 15-minute lock (config-driven). Reset-password clears the lock and revokes all sessions.
- **Public key for external JWT verification:** `GET /.well-known/jwks.json`.

---

## API Surface — Quick Reference

| Group | Method | Path | Auth | Purpose |
|-------|--------|------|------|---------|
| Auth | POST | `/auth/register` | Public | Create account (READER or AUTHOR) |
| Auth | POST | `/auth/verify-email` | Public | Confirm email with token |
| Auth | POST | `/auth/resend-verification` | Public | Resend verification email |
| Auth | POST | `/auth/login` | Public | Login → returns access + refresh tokens |
| Auth | POST | `/auth/refresh` | Public (uses refresh token) | Rotate tokens |
| Auth | POST | `/auth/forgot-password` | Public | Trigger password reset email |
| Auth | POST | `/auth/reset-password` | Public | Reset password using email token |
| Auth | POST | `/auth/logout` | USER/ADMIN | Revoke current refresh token |
| Auth | POST | `/auth/logout-all` | USER/ADMIN | Revoke all sessions |
| Auth | POST | `/auth/change-password` | USER/ADMIN | Change own password |
| Auth | GET | `/auth/me` | USER/ADMIN | Get current user summary |
| User | GET | `/user/profile` | USER/ADMIN | Read own profile |
| User | PUT | `/user/profile` | USER/ADMIN | Update own profile |
| Categories | GET | `/categories` | Public | List active categories |
| Categories | GET | `/categories/{id}` | Public | Get one category |
| Categories | GET | `/categories/admin/all` | ADMIN | List all (incl. inactive) |
| Categories | POST | `/categories` | ADMIN | Create category |
| Categories | PUT | `/categories/{id}` | ADMIN | Update category |
| Categories | DELETE | `/categories/{id}` | ADMIN | Delete category |
| Categories | PATCH | `/categories/{id}/toggle` | ADMIN | Activate / deactivate |
| Authors | GET | `/authors` | Public | List active authors |
| Books | GET | `/books` | Public | List published books |
| Books | GET | `/books/{id}` | Public | Get published book |
| Books | GET | `/books/category/{categoryId}` | Public | Published books in category |
| Books | GET | `/books/author/{authorId}` | Public | Published books by author |
| Books | GET | `/books/my` | USER/ADMIN (author) | Own books |
| Books | GET | `/books/my/{id}` | USER/ADMIN (author) | One own book |
| Books | POST | `/books` | USER/ADMIN (author) | Create book (→ PENDING) |
| Books | PUT | `/books/{id}` | USER/ADMIN (author) | Update own book (→ PENDING) |
| Books | DELETE | `/books/{id}` | USER/ADMIN (author) | Delete own book |
| Books | GET | `/books/my/{id}/history` | USER/ADMIN (author) | Own book approval log |
| Cart | GET | `/cart` | USER/ADMIN | View cart |
| Cart | POST | `/cart/items` | USER/ADMIN | Add book |
| Cart | DELETE | `/cart/items/{bookId}` | USER/ADMIN | Remove item |
| Cart | DELETE | `/cart` | USER/ADMIN | Clear cart |
| Cart | POST | `/cart/checkout` | USER/ADMIN | Purchase all items |
| Config | GET | `/config/params/keys?keys=a,b` | USER/ADMIN | Read specific config keys |
| Config | GET | `/config/params` | ADMIN | All config params |
| Config | POST | `/config/params/refresh` | ADMIN | Refresh config cache |
| Admin | POST | `/admin/seed` | `X-Seed-Secret` header | One-shot master data seed |
| Admin | GET | `/admin/authors` | ADMIN | List all authors |
| Admin | GET | `/admin/authors/{id}` | ADMIN | Get author |
| Admin | POST | `/admin/authors` | ADMIN | Create author (sends verify email) |
| Admin | PUT | `/admin/authors/{id}` | ADMIN | Update author |
| Admin | DELETE | `/admin/authors/{id}` | ADMIN | Deactivate author |
| Admin | PATCH | `/admin/authors/{id}/toggle` | ADMIN | Toggle author active |
| Admin | POST | `/admin/authors/{id}/resend-verification` | ADMIN | Resend author verify email |
| Admin | GET | `/admin/books` | ADMIN | All books (any status) |
| Admin | GET | `/admin/books/pending` | ADMIN | Pending review queue |
| Admin | GET | `/admin/books/{id}` | ADMIN | Get any book |
| Admin | PATCH | `/admin/books/{id}/approve` | ADMIN | Approve + publish |
| Admin | PATCH | `/admin/books/{id}/reject` | ADMIN | Reject with reason |
| Admin | GET | `/admin/books/{id}/history` | ADMIN | Approval log |
| Well-known | GET | `/.well-known/jwks.json` | Public | Public key for JWT verification |

---

## Auth APIs

### POST `/auth/register`

Creates account + profile. Sends verification email. If `userType=AUTHOR`, assigns USER role (authors log in with the same role).

**Body**

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `firstName` | string | Yes | NotBlank |
| `lastName` | string | Yes | NotBlank |
| `email` | string | Yes | Valid email, unique |
| `password` | string | Yes | Min 8 chars |
| `userType` | string | Yes | `READER` \| `AUTHOR` |

**Response `201`**

```json
{
  "success": true,
  "message": "User registered successfully",
  "data": {
    "id": "<uuid>",
    "email": "john@example.com",
    "userType": "READER",
    "status": "ACTIVE",
    "roles": ["USER"],
    "createdAt": "2026-04-01T10:30:00Z"
  }
}
```

**Errors:** `400` validation, `409` email already registered.

---

### POST `/auth/verify-email`

**Body**: `{ "token": "<from email link>" }`
**Response `200`**: `data: null`, message `"Email verified successfully"`.
**Errors:** `400` invalid/expired/already-used token, `400` already verified.

---

### POST `/auth/resend-verification`

**Body**: `{ "email": "user@example.com" }`. Always returns the same message — prevents enumeration.

---

### POST `/auth/login`

**Body**

| Field | Type | Required |
|-------|------|----------|
| `email` | string | Yes |
| `password` | string | Yes |
| `deviceFingerprint` | string | Yes |

**Response `200`**

```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "opaque-string",
    "expiresIn": 900
  }
}
```

**Errors:** `401` invalid creds / locked / unverified.

---

### POST `/auth/refresh`

**Body**: `{ "refreshToken": "...", "deviceFingerprint": "..." }`
Returns a **new** access + refresh token pair. **Old refresh token is revoked immediately.**

---

### POST `/auth/forgot-password`

**Body**: `{ "email": "..." }`. Always returns the same message — prevents enumeration. Reset link expires in 1 hour.

---

### POST `/auth/reset-password`

**Body**: `{ "token": "...", "newPassword": "min 8 chars" }`
Side effects: unlocks account, revokes **all** sessions.

---

### POST `/auth/logout`  *(auth required)*

**Body**: `{ "refreshToken": "...", "deviceFingerprint": "..." }` — revokes only this session.

---

### POST `/auth/logout-all`  *(auth required)*

No body. Revokes all refresh tokens for the user.

---

### POST `/auth/change-password`  *(auth required)*

**Body**: `{ "currentPassword": "...", "newPassword": "min 8 chars" }`
Side effect: revokes all sessions.

**Errors:** `401` wrong current password, `400` new == current.

---

### GET `/auth/me`  *(auth required)*

Returns the same `UserResponse` shape as register.

---

## User Profile APIs

### GET `/user/profile`  *(auth required)*

```json
{
  "success": true,
  "message": "Profile retrieved successfully",
  "data": {
    "id": "<uuid>",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "phone": "+1234567890",
    "interests": "Fiction, Technology",
    "designation": "Dr.",
    "description": "Short bio",
    "isActive": true,
    "createdAt": "2026-04-01T10:30:00Z",
    "updatedAt": "2026-04-01T12:00:00Z"
  }
}
```

### PUT `/user/profile`  *(auth required)*

| Field | Type | Required |
|-------|------|----------|
| `firstName` | string | Yes |
| `lastName` | string | Yes |
| `phone` | string | No |
| `interests` | string | No |
| `designation` | string | No |
| `description` | string | No |

Returns updated profile.

---

## Category APIs

### GET `/categories`  *(public)*

Returns active categories only. Cache-friendly for SSR / ISR.

```json
{
  "success": true,
  "message": "Categories retrieved",
  "data": [
    {
      "id": "<uuid>",
      "name": "Pulmonology",
      "slug": "pulmonology",
      "description": "Lungs and respiratory system",
      "isActive": true,
      "createdAt": "2026-04-01T10:30:00Z",
      "updatedAt": "2026-04-01T10:30:00Z"
    }
  ]
}
```

### GET `/categories/{id}`  *(public)*

Returns single category. `404` if not found.

### GET `/categories/admin/all`  *(ADMIN)*

Includes inactive categories.

### POST `/categories`  *(ADMIN)*

**Body**: `{ "name": "Cardiology" (required, max 100), "description": "..." (optional) }`. Slug auto-generated from name.
Returns `201` + CategoryResponse.

### PUT `/categories/{id}`  *(ADMIN)*

Same body as create.

### DELETE `/categories/{id}`  *(ADMIN)*

`200`, `data: null`.

### PATCH `/categories/{id}/toggle`  *(ADMIN)*

Flips `isActive`. Message reflects new state.

---

## Author APIs

### GET `/authors`  *(public)*

Returns active authors. Useful for public author-listing pages.

```json
{
  "success": true,
  "data": [
    {
      "id": "<uuid>",
      "email": "author@example.com",
      "firstName": "Jane",
      "lastName": "Smith",
      "phone": "+1...",
      "designation": "Dr.",
      "description": "Bio",
      "emailVerified": true,
      "isActive": true,
      "status": "ACTIVE",
      "createdAt": "...",
      "updatedAt": "..."
    }
  ]
}
```

(Admin-only author CRUD is under [Admin APIs](#admin-apis).)

---

## Book APIs

### BookResponse shape

```json
{
  "id": "<uuid>",
  "title": "Respiratory Physiology",
  "description": "...",
  "price": 29.99,
  "discount": 5.00,
  "keywords": "lung, respiration",
  "publishedDate": "2026-04-10T10:00:00Z",
  "pages": 420,
  "coverUrl": "https://cdn.../cover.jpg",
  "previewUrl": "https://cdn.../preview.pdf",
  "versionNumber": "1.0.0",
  "isPublished": true,
  "status": "APPROVED",
  "rejectionReason": null,
  "categoryId": "<uuid>",
  "categoryName": "Pulmonology",
  "authorId": "<uuid>",
  "authorName": "Jane Smith",
  "createdAt": "...",
  "updatedAt": "..."
}
```

### Public Book Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /books` | All published (status APPROVED) books |
| `GET /books/{id}` | One published book |
| `GET /books/category/{categoryId}` | Published books in a category |
| `GET /books/author/{authorId}` | Published books by an author |

`404` if the book is not published or doesn't exist — safe to use for dynamic route 404s.

### Author (Own-Book Management) Endpoints  *(auth required)*

| Endpoint | Description |
|----------|-------------|
| `GET /books/my` | List own books (any status) |
| `GET /books/my/{id}` | Get own book by id |
| `POST /books` | Create book → status `PENDING`, awaits admin |
| `PUT /books/{id}` | Update own book → resets to `PENDING` |
| `DELETE /books/{id}` | Soft-delete own book |
| `GET /books/my/{id}/history` | Own book approval history |

**CreateBookRequest body**

| Field | Type | Required |
|-------|------|----------|
| `title` | string | Yes (NotBlank) |
| `description` | string | No |
| `price` | number | Yes (NotNull) |
| `discount` | number | No |
| `keywords` | string | No — use CSV for SEO |
| `pages` | integer | No |
| `coverUrl` | string | No |
| `previewUrl` | string | No |
| `versionNumber` | string | No |
| `categoryId` | UUID | Yes (NotNull) |

**UpdateBookRequest** = CreateBookRequest + optional `message` (note to admin).

**BookApprovalLogResponse**

```json
{
  "id": "<uuid>",
  "bookId": "<uuid>",
  "bookTitle": "...",
  "senderId": "<uuid>",
  "senderEmail": "author@...",
  "receiverId": "<uuid>",
  "receiverEmail": "admin@...",
  "action": "SUBMITTED",
  "message": "Initial submission",
  "createdAt": "..."
}
```

`action` ∈ `SUBMITTED`, `RESUBMITTED`, `APPROVED`, `REJECTED`, `DELETION_REQUESTED`.
`status` ∈ `PENDING`, `APPROVED`, `REJECTED`, `DELETED`.

---

## Cart & Checkout APIs

All cart routes require auth (`USER` or `ADMIN`).

### GET `/cart`

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "bookId": "<uuid>",
        "title": "...",
        "authorName": "...",
        "categoryName": "...",
        "coverUrl": "...",
        "price": 29.99,
        "discount": 5.00,
        "effectivePrice": 24.99,
        "addedAt": "..."
      }
    ],
    "totalItems": 1,
    "totalPrice": 24.99
  }
}
```

### POST `/cart/items`

**Body**: `{ "bookId": "<uuid>" }` → `201` + `CartItemResponse`.

### DELETE `/cart/items/{bookId}`

Removes a single item.

### DELETE `/cart`

Clears the cart.

### POST `/cart/checkout`

Completes purchase for all cart items. Creates a Payment record, unlocks books for the user, empties the cart.

```json
{
  "success": true,
  "message": "Purchase completed successfully",
  "data": {
    "paymentId": "<uuid>",
    "totalAmount": 49.98,
    "itemsPurchased": 2,
    "status": "COMPLETED"
  }
}
```

---

## Config APIs

### GET `/config/params/keys?keys=auth.session-expiry-days,app.frontend-url`  *(USER/ADMIN)*

Comma-separated `keys` query param. Returns values for whitelisted keys.

```json
{
  "success": true,
  "data": [
    {
      "name": "Frontend Base URL",
      "key": "app.frontend-url",
      "value": "http://localhost:3000",
      "defaultValue": "http://localhost:3000",
      "possibleValues": null,
      "type": "STRING"
    }
  ]
}
```

### GET `/config/params`  *(ADMIN)*

Returns all config params.

### POST `/config/params/refresh`  *(ADMIN)*

Forces server to reload config from DB.

---

## Admin APIs

### POST `/admin/seed`

Header: `X-Seed-Secret: <value from SEED_SECRET env>`. Public endpoint, secret-gated. Idempotent — won't double-seed.

### Admin Author CRUD — `/admin/authors*`

| Method | Path | Body | Notes |
|--------|------|------|-------|
| GET | `/admin/authors` | — | All authors |
| GET | `/admin/authors/{id}` | — | One author |
| POST | `/admin/authors` | CreateAuthorRequest | Creates user + sends verification email |
| PUT | `/admin/authors/{id}` | UpdateAuthorRequest | No email change |
| DELETE | `/admin/authors/{id}` | — | Deactivates (soft) |
| PATCH | `/admin/authors/{id}/toggle` | — | Activate/deactivate |
| POST | `/admin/authors/{id}/resend-verification` | — | Resend invite |

**CreateAuthorRequest**

| Field | Required | Validation |
|-------|----------|------------|
| `email` | Yes | Valid email |
| `firstName` | Yes | NotBlank |
| `lastName` | Yes | NotBlank |
| `phone` | No | — |
| `designation` | No | — |
| `description` | No | — |

**UpdateAuthorRequest** — same as above minus `email`.

### Admin Book Moderation — `/admin/books*`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/books` | All books (any status) |
| GET | `/admin/books/pending` | Review queue — status `PENDING` only |
| GET | `/admin/books/{id}` | Any book |
| PATCH | `/admin/books/{id}/approve` | Status → `APPROVED`, `isPublished=true` |
| PATCH | `/admin/books/{id}/reject` | Body: `{ "reason": "..." }` (optional) |
| GET | `/admin/books/{id}/history` | Full approval log |

---

## Internal / Well-Known

### GET `/.well-known/jwks.json`

Public key in JWKS format for any downstream service that needs to verify the access token signature (RS256). No auth.

---

## Error Codes Reference

| HTTP | Meaning | Typical trigger |
|------|---------|-----------------|
| `400` | Bad Request / Validation | Missing/invalid fields, business-rule violation |
| `401` | Unauthorized | Bad creds, expired/invalid token, unverified email, locked account |
| `403` | Forbidden | Role-based denial (e.g. USER hitting ADMIN route) |
| `404` | Not Found | Resource missing or not visible to caller |
| `405` | Method Not Allowed | Wrong HTTP verb |
| `409` | Conflict | Duplicate (email, slug) |
| `429` | Too Many Requests | Rate-limit exceeded. Includes `Retry-After` header |
| `500` | Internal Server Error | Unhandled server-side failure |

---

## Enums Reference

| Enum | Values |
|------|--------|
| `UserType` | `READER`, `AUTHOR` |
| `UserStatus` | `ACTIVE`, `LOCKED`, `DISABLED` |
| `RoleName` | `USER`, `ADMIN` |
| `BookStatus` | `PENDING`, `APPROVED`, `REJECTED`, `DELETED` |
| `BookApprovalAction` | `SUBMITTED`, `RESUBMITTED`, `APPROVED`, `REJECTED`, `DELETION_REQUESTED` |

---

## Next.js Integration Guide

Targeting **Next.js 14+ App Router** with Server Components, Server Actions, and SSR/ISR for SEO.

### 1. Env Setup

```bash
# .env.local
NEXT_PUBLIC_API_URL=http://localhost:8080/ebook       # used in browser (RSC can also read)
API_URL=http://localhost:8080/ebook                   # server-only
```

Keep secrets server-side. Tokens should live in **HttpOnly cookies** — never in `localStorage` for an SSR app, because Server Components can't read `localStorage`.

### 2. Recommended Folder Structure

```
app/
  (public)/
    page.tsx                        # home
    books/
      page.tsx                      # book list (SSR + ISR)
      [id]/page.tsx                 # book detail (generateMetadata)
    category/[slug]/page.tsx
    author/[id]/page.tsx
  (auth)/
    login/page.tsx
    register/page.tsx
    verify-email/page.tsx
    forgot-password/page.tsx
    reset-password/page.tsx
  (protected)/
    profile/page.tsx
    my-books/page.tsx
    cart/page.tsx
  (admin)/
    admin/layout.tsx
    admin/books/page.tsx
lib/
  api/
    client.ts                       # fetch wrapper
    auth.ts
    books.ts
    categories.ts
    cart.ts
  session.ts                        # cookie helpers
middleware.ts                       # route guard
```

### 3. Token Strategy (HttpOnly cookies)

Store `accessToken` and `refreshToken` in HttpOnly cookies set via a Next.js route handler so browser JS never sees them:

```ts
// app/api/auth/login/route.ts
import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';

export async function POST(req: Request) {
  const body = await req.json();
  const res = await fetch(`${process.env.API_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) return NextResponse.json(json, { status: res.status });

  const { accessToken, refreshToken, expiresIn } = json.data;
  const c = await cookies();
  c.set('access_token', accessToken, {
    httpOnly: true, secure: true, sameSite: 'lax', path: '/',
    maxAge: expiresIn,
  });
  c.set('refresh_token', refreshToken, {
    httpOnly: true, secure: true, sameSite: 'lax', path: '/',
    maxAge: 60 * 60 * 24 * 30, // 30 days
  });
  return NextResponse.json({ success: true });
}
```

### 4. Server-Side API Client

```ts
// lib/api/client.ts
import { cookies } from 'next/headers';

const BASE = process.env.API_URL!;

type Options = RequestInit & {
  auth?: boolean;
  revalidate?: number | false;  // seconds, or false for no-store
  tags?: string[];
};

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const { auth = false, revalidate, tags, ...init } = opts;
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/json');

  if (auth) {
    const token = (await cookies()).get('access_token')?.value;
    if (token) headers.set('Authorization', `Bearer ${token}`);
  }

  const next: any = {};
  if (revalidate === false) next.cache = 'no-store';
  else if (typeof revalidate === 'number') next.next = { revalidate, tags };

  const res = await fetch(`${BASE}${path}`, { ...init, headers, ...next });
  const json = await res.json();
  if (!res.ok) throw new ApiError(res.status, json?.message ?? 'Request failed', json);
  return json as T;
}

export class ApiError extends Error {
  constructor(public status: number, msg: string, public body?: unknown) {
    super(msg);
  }
}
```

### 5. Server Component Data Fetching (SSR + ISR)

```ts
// app/(public)/books/[id]/page.tsx
import { api } from '@/lib/api/client';
import { notFound } from 'next/navigation';
import type { Metadata } from 'next';

type BookResponse = { /* see BookResponse schema above */ };
type ApiEnvelope<T> = { success: boolean; message: string; data: T };

async function getBook(id: string) {
  try {
    const res = await api<ApiEnvelope<BookResponse>>(`/books/${id}`, {
      revalidate: 300,                 // ISR: 5-min cache
      tags: [`book:${id}`],            // for on-demand revalidation
    });
    return res.data;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export async function generateMetadata(
  { params }: { params: Promise<{ id: string }> }
): Promise<Metadata> {
  const { id } = await params;
  const book = await getBook(id);
  if (!book) return { title: 'Book not found' };
  return {
    title: `${book.title} — ebookHub`,
    description: book.description?.slice(0, 160),
    keywords: book.keywords,
    openGraph: {
      title: book.title,
      description: book.description ?? undefined,
      images: book.coverUrl ? [book.coverUrl] : [],
      type: 'book',
    },
    alternates: { canonical: `/books/${book.id}` },
  };
}

export default async function BookPage(
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params;
  const book = await getBook(id);
  if (!book) notFound();
  return <BookDetail book={book} />;
}
```

### 6. Static Params for SEO-Critical Routes

```ts
// app/(public)/category/[slug]/page.tsx
export async function generateStaticParams() {
  const res = await fetch(`${process.env.API_URL}/categories`, {
    next: { revalidate: 3600 },
  });
  const json = await res.json();
  return json.data.map((c: { slug: string }) => ({ slug: c.slug }));
}
```

### 7. Mutations — Server Actions

```ts
// app/(protected)/profile/actions.ts
'use server';
import { api } from '@/lib/api/client';
import { revalidatePath } from 'next/cache';

export async function updateProfile(form: FormData) {
  await api('/user/profile', {
    method: 'PUT',
    auth: true,
    body: JSON.stringify({
      firstName: form.get('firstName'),
      lastName: form.get('lastName'),
      phone: form.get('phone'),
    }),
    revalidate: false,
  });
  revalidatePath('/profile');
}
```

### 8. Auth-Gated Mutations + Auto-Refresh

Server Actions can't easily run an axios interceptor. Instead: detect `401`, call `/auth/refresh` via a helper, retry once.

```ts
// lib/api/auth-fetch.ts
'use server';
import { cookies } from 'next/headers';
import { api, ApiError } from './client';

async function refreshTokens() {
  const c = await cookies();
  const refresh = c.get('refresh_token')?.value;
  if (!refresh) throw new ApiError(401, 'No refresh token');
  const res = await fetch(`${process.env.API_URL}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      refreshToken: refresh,
      deviceFingerprint: c.get('device_fp')?.value ?? 'server',
    }),
    cache: 'no-store',
  });
  if (!res.ok) throw new ApiError(401, 'Refresh failed');
  const { data } = await res.json();
  c.set('access_token', data.accessToken, {
    httpOnly: true, secure: true, sameSite: 'lax', path: '/', maxAge: data.expiresIn,
  });
  c.set('refresh_token', data.refreshToken, {
    httpOnly: true, secure: true, sameSite: 'lax', path: '/', maxAge: 60 * 60 * 24 * 30,
  });
}

export async function authApi<T>(path: string, opts: Parameters<typeof api>[1] = {}) {
  try {
    return await api<T>(path, { ...opts, auth: true });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      await refreshTokens();
      return await api<T>(path, { ...opts, auth: true });
    }
    throw e;
  }
}
```

### 9. Route Protection via Middleware

```ts
// middleware.ts
import { NextResponse, type NextRequest } from 'next/server';

export function middleware(req: NextRequest) {
  const hasAccess = req.cookies.has('access_token');
  const hasRefresh = req.cookies.has('refresh_token');
  const { pathname } = req.nextUrl;

  const needsAuth = pathname.startsWith('/profile')
    || pathname.startsWith('/my-books')
    || pathname.startsWith('/cart')
    || pathname.startsWith('/admin');

  if (needsAuth && !hasAccess && !hasRefresh) {
    const url = req.nextUrl.clone();
    url.pathname = '/login';
    url.searchParams.set('from', pathname);
    return NextResponse.redirect(url);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/profile/:path*', '/my-books/:path*', '/cart/:path*', '/admin/:path*'],
};
```

For **role-based** guarding (ADMIN routes), decode the JWT and check `groups` in a Server Component layout, or in a thin API route.

### 10. Device Fingerprint

The backend requires `deviceFingerprint` on login/refresh/logout. Generate on the client, POST it to the login route, store it in a **non-HttpOnly** cookie so server code can read it during refresh:

```ts
// lib/fingerprint.ts
export function getFingerprint() {
  if (typeof window === 'undefined') return 'server';
  const key = 'device_fp';
  let fp = document.cookie.match(/device_fp=([^;]+)/)?.[1];
  if (!fp) {
    fp = crypto.randomUUID();
    document.cookie = `${key}=${fp}; path=/; max-age=31536000; SameSite=Lax`;
  }
  return fp;
}
```

### 11. SEO Patterns

| Need | Approach |
|------|----------|
| Book/Author/Category pages | `generateMetadata` with data from `/books/{id}`, `/authors`, `/categories/{id}` |
| Open Graph images | Use `coverUrl` from `BookResponse` |
| Structured data | Emit JSON-LD `<script type="application/ld+json">` for `Book`, `Person`, `Product` (cart-eligible books) |
| Sitemaps | `app/sitemap.ts` — fetch `/books`, `/categories`, `/authors` with `{ next: { revalidate: 3600 } }` |
| Canonicals | `alternates.canonical` per detail page |
| 404s | Throw `notFound()` when API returns `404` |

**Sitemap example:**

```ts
// app/sitemap.ts
import type { MetadataRoute } from 'next';

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const base = process.env.NEXT_PUBLIC_SITE_URL!;
  const [booksRes, catsRes] = await Promise.all([
    fetch(`${process.env.API_URL}/books`, { next: { revalidate: 3600 } }).then(r => r.json()),
    fetch(`${process.env.API_URL}/categories`, { next: { revalidate: 3600 } }).then(r => r.json()),
  ]);
  return [
    { url: base, lastModified: new Date() },
    ...booksRes.data.map((b: any) => ({
      url: `${base}/books/${b.id}`, lastModified: b.updatedAt,
    })),
    ...catsRes.data.map((c: any) => ({
      url: `${base}/category/${c.slug}`, lastModified: c.updatedAt,
    })),
  ];
}
```

### 12. Cache / Revalidation Strategy

| Data | Strategy |
|------|----------|
| `/categories`, `/authors` | `revalidate: 3600`, tag `categories` / `authors` |
| `/books` list | `revalidate: 300`, tag `books` |
| `/books/{id}` | `revalidate: 300`, tag `book:{id}` |
| `/user/profile`, `/cart`, `/books/my*` | `cache: 'no-store'` (per-user) |
| Admin endpoints | `cache: 'no-store'` |

After a mutation (e.g. admin approves book), call `revalidateTag('book:{id}')` and `revalidateTag('books')` from the Server Action.

### 13. CORS

The backend already allows `http://localhost:3000` and `http://localhost:5173`. Add your production frontend origin to `quarkus.http.cors.origins` in `application.properties` before deploying.

### 14. Error Handling Pattern

```tsx
// app/error.tsx
'use client';
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div>
      <h2>Something went wrong</h2>
      <p>{error.message}</p>
      <button onClick={reset}>Try again</button>
    </div>
  );
}
```

For `401` surfaced from Server Actions, catch in the action and return a typed failure object so the client form can show the error and redirect to `/login` if needed.

---

## Appendix — Frontend Routes Expected by Email Templates

These URLs are embedded in emails the backend sends; your Next.js app must implement them:

| Email | Link pattern |
|-------|--------------|
| Verification | `${app.frontend-url}/verify-email?token=<token>` |
| Password reset | `${app.frontend-url}/reset-password?token=<token>` |
| Author invite | `${app.frontend-url}/verify-email?token=<token>` (same as registration verify) |

Configure `app.frontend-url` via the admin config endpoint or `application.properties`.
