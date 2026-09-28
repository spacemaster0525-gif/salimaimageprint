import ctypes
from jb import lib
CB=ctypes.CFUNCTYPE(None,ctypes.POINTER(ctypes.c_ubyte),ctypes.c_size_t,ctypes.c_void_p)
lib.jbg_enc_init.argtypes=[ctypes.c_void_p,ctypes.c_ulong,ctypes.c_ulong,ctypes.c_int,ctypes.POINTER(ctypes.c_char_p),CB,ctypes.c_void_p]
lib.jbg_enc_options.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_ulong,ctypes.c_int,ctypes.c_int]
lib.jbg_enc_lrlmax.argtypes=[ctypes.c_void_p,ctypes.c_ulong,ctypes.c_ulong]
lib.jbg_enc_layers.argtypes=[ctypes.c_void_p,ctypes.c_int]
lib.jbg_enc_out.argtypes=[ctypes.c_void_p]
lib.jbg_enc_free.argtypes=[ctypes.c_void_p]
def enc(img,w,h,opts=0x48,order=0,l0=128,mx=0,my=0):
    out=bytearray()
    @CB
    def cb(p,n,f): out.extend(bytes(p[:n]))
    s=ctypes.create_string_buffer(65536)
    buf=ctypes.create_string_buffer(img,len(img))
    arr=(ctypes.c_char_p*1)(ctypes.cast(buf,ctypes.c_char_p))
    lib.jbg_enc_init(s,w,h,1,arr,cb,None)
    lib.jbg_enc_layers(s,0)
    lib.jbg_enc_options(s,order,opts,l0,mx,my)
    lib.jbg_enc_out(s); lib.jbg_enc_free(s)
    return bytes(out)
