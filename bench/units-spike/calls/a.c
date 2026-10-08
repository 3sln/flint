extern int f(int);
#ifdef SINGLE
__attribute__((noinline)) int f(int x) { return x * 3 + 1; }
#endif
int (*volatile fp)(int) = f;
__attribute__((export_name("direct"))) int direct(int n) { int s = 0; for (int i = 0; i < n; i++) s = f(s + i); return s; }
__attribute__((export_name("indirect"))) int indirect(int n) { int s = 0; for (int i = 0; i < n; i++) s = fp(s + i); return s; }
