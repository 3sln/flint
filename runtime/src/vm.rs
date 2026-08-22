//! The interpreter. (Under construction: `invoke` is the entry point native
//! code uses to call back into Clojure -- lazy-seq forcing, comparators,
//! higher-order builtins -- and it works because the VM's state is all in `Rt`,
//! so re-entering is just another frame on the same value stack.)

use crate::rt::Rt;
use crate::value::{Value, NIL};

impl Rt {
    pub fn invoke(&mut self, f: Value, _args: &[Value]) -> Value {
        let _ = f;
        self.throw_str("IllegalStateException", "the interpreter is not installed yet");
        NIL
    }
}
