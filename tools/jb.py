import ctypes, struct
lib=ctypes.CDLL('libjbig.so.0')
lib.jbg_dec_in.argtypes=[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_size_t,ctypes.POINTER(ctypes.c_size_t)]
lib.jbg_dec_getimage.restype=ctypes.POINTER(ctypes.c_ubyte)
lib.jbg_dec_getimage.argtypes=[ctypes.c_void_p,ctypes.c_int]
lib.jbg_dec_getwidth.restype=ctypes.c_ulong; lib.jbg_dec_getheight.restype=ctypes.c_ulong
lib.jbg_dec_getwidth.argtypes=lib.jbg_dec_getheight.argtypes=[ctypes.c_void_p]
lib.jbg_strerror.restype=ctypes.c_char_p
def bih(xd=4720,yd=256,l0=128,opts=0x48,order=0):
    return struct.pack('>BBBBIIIBBBB',0,0,1,0,xd,yd,l0,0,0,order,opts)
def dec(data,xd=4720,yd=256,l0=128,opts=0x48,order=0):
    s=ctypes.create_string_buffer(65536)
    lib.jbg_dec_init(s)
    st=bih(xd,yd,l0,opts,order)+data
    cnt=ctypes.c_size_t(0)
    r=lib.jbg_dec_in(s,st,len(st),ctypes.byref(cnt))
    msg=lib.jbg_strerror(r,0)
    img=None
    if r==0:
        w=lib.jbg_dec_getwidth(s);h=lib.jbg_dec_getheight(s);bpl=(w+7)//8
        p=lib.jbg_dec_getimage(s,0);img=bytes(p[:bpl*h])
    lib.jbg_dec_free(s)
    return r,msg,cnt.value,img
