#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include "blake2.h"

static const uint64_t blake2b_IV[8] = {
    0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL,
    0x3c6ef372fe94f82bULL, 0xa54ff53a5f1d36f1ULL,
    0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL,
    0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL
};

static const uint8_t blake2b_sigma[12][16] = {
    {  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15 },
    { 14, 10,  4,  8,  9, 15, 13,  6,  1, 12,  0,  2, 11,  7,  5,  3 },
    { 11,  8, 12,  0,  5,  2, 15, 13, 10, 14,  3,  6,  7,  1,  9,  4 },
    {  7,  9,  3,  1, 13, 12, 11, 14,  2,  6,  5, 10,  4,  0, 15,  8 },
    {  9,  0,  5,  7,  2,  4, 10, 15, 14,  1, 11, 12,  6,  8,  3, 13 },
    {  2, 12,  6, 10,  0, 11,  8,  3,  4, 13,  7,  5, 15, 14,  1,  9 },
    { 12,  5,  1, 15, 14, 13,  4, 10,  0,  7,  6,  3,  9,  2,  8, 11 },
    { 13, 11,  7, 14, 12,  1,  3,  9,  5,  0, 15,  4,  8,  6,  2, 10 },
    {  6, 15, 14,  9, 11,  3,  0,  8, 12,  2, 13,  7,  1,  4, 10,  5 },
    { 10,  2,  8,  4,  7,  6,  1,  5, 15, 11,  9, 14,  3, 12, 13,  0 },
    {  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15 },
    { 14, 10,  4,  8,  9, 15, 13,  6,  1, 12,  0,  2, 11,  7,  5,  3 }
};

static inline uint64_t rotr64(const uint64_t w, const unsigned c) {
    return (w >> c) | (w << (64 - c));
}

static inline void blake2b_set_lastnode(blake2b_state *S) {
    S->f[1] = (uint64_t)-1;
}

static inline void blake2b_set_lastblock(blake2b_state *S) {
    if (S->last_node) blake2b_set_lastnode(S);
    S->f[0] = (uint64_t)-1;
}

static inline void blake2b_increment_counter(blake2b_state *S, const uint64_t inc) {
    S->t[0] += inc;
    S->t[1] += (S->t[0] < inc);
}

static inline uint64_t load64(const void *src) {
    uint64_t w;
    memcpy(&w, src, sizeof(w));
    return w;
}

static inline void store64(void *dst, uint64_t w) {
    memcpy(dst, &w, sizeof(w));
}

#define G(r,i,a,b,c,d) \
  do { \
    a = a + b + m[blake2b_sigma[r][2*i+0]]; \
    d = rotr64(d ^ a, 32); \
    c = c + d; \
    b = rotr64(b ^ c, 24); \
    a = a + b + m[blake2b_sigma[r][2*i+1]]; \
    d = rotr64(d ^ a, 16); \
    c = c + d; \
    b = rotr64(b ^ c, 63); \
  } while(0)

#define ROUND(r)  \
  do { \
    G(r,0,v[ 0],v[ 4],v[ 8],v[12]); \
    G(r,1,v[ 1],v[ 5],v[ 9],v[13]); \
    G(r,2,v[ 2],v[ 6],v[10],v[14]); \
    G(r,3,v[ 3],v[ 7],v[11],v[15]); \
    G(r,4,v[ 0],v[ 5],v[10],v[15]); \
    G(r,5,v[ 1],v[ 6],v[11],v[12]); \
    G(r,6,v[ 2],v[ 7],v[ 8],v[13]); \
    G(r,7,v[ 3],v[ 4],v[ 9],v[14]); \
  } while(0)

static void blake2b_compress(blake2b_state *S, const uint8_t block[BLAKE2B_BLOCKBYTES]) {
    uint64_t m[16];
    uint64_t v[16];
    size_t i;

    for (i = 0; i < 16; ++i) {
        m[i] = load64(block + i * sizeof(m[i]));
    }

    for (i = 0; i < 8; ++i) {
        v[i] = S->h[i];
    }

    v[ 8] = blake2b_IV[0];
    v[ 9] = blake2b_IV[1];
    v[10] = blake2b_IV[2];
    v[11] = blake2b_IV[3];
    v[12] = S->t[0] ^ blake2b_IV[4];
    v[13] = S->t[1] ^ blake2b_IV[5];
    v[14] = S->f[0] ^ blake2b_IV[6];
    v[15] = S->f[1] ^ blake2b_IV[7];

    ROUND(0);
    ROUND(1);
    ROUND(2);
    ROUND(3);
    ROUND(4);
    ROUND(5);
    ROUND(6);
    ROUND(7);
    ROUND(8);
    ROUND(9);
    ROUND(10);
    ROUND(11);

    for (i = 0; i < 8; ++i) {
        S->h[i] = S->h[i] ^ v[i] ^ v[i + 8];
    }
}

int blake2b_init(blake2b_state *S, size_t outlen) {
    size_t i;
    if (!outlen || outlen > BLAKE2B_OUTBYTES) return -1;

    for (i = 0; i < 8; ++i) S->h[i] = blake2b_IV[i];
    S->h[0] ^= 0x01010000 ^ (uint8_t)outlen;
    S->t[0] = 0;
    S->t[1] = 0;
    S->f[0] = 0;
    S->f[1] = 0;
    S->buflen = 0;
    S->outlen = outlen;
    S->last_node = 0;
    memset(S->buf, 0, sizeof(S->buf));
    return 0;
}

int blake2b_init_key(blake2b_state *S, size_t outlen, const void *key, size_t keylen) {
    uint8_t block[BLAKE2B_BLOCKBYTES];
    size_t i;
    if (!outlen || outlen > BLAKE2B_OUTBYTES) return -1;
    if (!key || !keylen || keylen > BLAKE2B_KEYBYTES) return -1;

    for (i = 0; i < 8; ++i) S->h[i] = blake2b_IV[i];
    S->h[0] ^= 0x01010000 ^ ((uint8_t)keylen << 8) ^ (uint8_t)outlen;
    S->t[0] = 0;
    S->t[1] = 0;
    S->f[0] = 0;
    S->f[1] = 0;
    S->buflen = 0;
    S->outlen = outlen;
    S->last_node = 0;
    memset(S->buf, 0, sizeof(S->buf));

    memset(block, 0, BLAKE2B_BLOCKBYTES);
    memcpy(block, key, keylen);
    blake2b_update(S, block, BLAKE2B_BLOCKBYTES);
    memset(block, 0, BLAKE2B_BLOCKBYTES);
    return 0;
}

int blake2b_update(blake2b_state *S, const void *pin, size_t inlen) {
    const uint8_t *in = (const uint8_t *)pin;
    if (inlen > 0) {
        size_t left = S->buflen;
        size_t fill = BLAKE2B_BLOCKBYTES - left;
        if (inlen > fill) {
            S->buflen = 0;
            memcpy(S->buf + left, in, fill);
            blake2b_increment_counter(S, BLAKE2B_BLOCKBYTES);
            blake2b_compress(S, S->buf);
            in += fill;
            inlen -= fill;
            while (inlen > BLAKE2B_BLOCKBYTES) {
                blake2b_increment_counter(S, BLAKE2B_BLOCKBYTES);
                blake2b_compress(S, in);
                in += BLAKE2B_BLOCKBYTES;
                inlen -= BLAKE2B_BLOCKBYTES;
            }
        }
        memcpy(S->buf + S->buflen, in, inlen);
        S->buflen += inlen;
    }
    return 0;
}

int blake2b_final(blake2b_state *S, void *out, size_t outlen) {
    uint8_t buffer[BLAKE2B_OUTBYTES] = {0};
    size_t i;

    if (out == NULL || outlen < S->outlen) return -1;

    if (S->buflen > 0) {
        memset(S->buf + S->buflen, 0, BLAKE2B_BLOCKBYTES - S->buflen);
    }
    blake2b_increment_counter(S, S->buflen);
    blake2b_set_lastblock(S);
    blake2b_compress(S, S->buf);

    for (i = 0; i < 8; ++i) {
        store64(buffer + i * sizeof(S->h[i]), S->h[i]);
    }

    memcpy(out, buffer, S->outlen);
    memset(buffer, 0, sizeof(buffer));
    return 0;
}

int blake2b(void *out, size_t outlen, const void *in, size_t inlen, const void *key, size_t keylen) {
    blake2b_state S;
    if (keylen > 0) {
        if (blake2b_init_key(&S, outlen, key, keylen) < 0) return -1;
    } else {
        if (blake2b_init(&S, outlen) < 0) return -1;
    }
    blake2b_update(&S, in, inlen);
    blake2b_final(&S, out, outlen);
    return 0;
}
