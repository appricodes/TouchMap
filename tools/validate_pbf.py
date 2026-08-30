"""Mirror of the Java PbfReader manual decode, to validate the algorithm against a real file.
Implements the same steps: 4-byte BlobHeader length, BlobHeader, Blob(zlib), PrimitiveBlock,
StringTable, DenseNodes (delta+zigzag+granularity/offset), Ways (packed refs delta)."""
import struct, sys, zlib

def read_varint(buf, pos):
    result = 0; shift = 0
    while True:
        b = buf[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80): break
        shift += 7
    return result, pos

def zigzag(n):
    return (n >> 1) ^ -(n & 1)

def parse_fields(buf, start, end):
    """Yield (field, wire, value_or_span) — for wire 2 yields (start,end) span."""
    pos = start
    while pos < end:
        tag, pos = read_varint(buf, pos)
        field = tag >> 3; wire = tag & 7
        if wire == 0:
            v, pos = read_varint(buf, pos); yield field, wire, v
        elif wire == 2:
            ln, pos = read_varint(buf, pos); yield field, wire, (pos, pos+ln); pos += ln
        elif wire == 1:
            yield field, wire, buf[pos:pos+8]; pos += 8
        elif wire == 5:
            yield field, wire, buf[pos:pos+4]; pos += 4
        else:
            raise ValueError("wire %d" % wire)

def packed_varints(buf, s, e):
    out = []; pos = s
    while pos < e:
        v, pos = read_varint(buf, pos); out.append(v)
    return out

def main(path):
    data = open(path, "rb").read()
    pos = 0
    nodes = 0; ways = 0; node_coords = {}
    sample_nodes = []; sample_ways = []
    lat_min=lat_max=lon_min=lon_max=None
    while pos < len(data):
        (hlen,) = struct.unpack_from(">i", data, pos); pos += 4
        hstart = pos; hend = pos + hlen; pos = hend
        htype = None; dsize = 0
        for f, w, v in parse_fields(data, hstart, hend):
            if f == 1: htype = data[v[0]:v[1]].decode("utf-8")
            elif f == 3: dsize = v
        bstart = pos; bend = pos + dsize; pos = bend
        raw = None; zdata = None
        for f, w, v in parse_fields(data, bstart, bend):
            if f == 1: raw = data[v[0]:v[1]]
            elif f == 3: zdata = data[v[0]:v[1]]
        block = raw if raw is not None else zlib.decompress(zdata)
        if htype != "OSMData":
            continue
        # PrimitiveBlock
        st = []; groups = []; gran = 100; lat_off = 0; lon_off = 0
        for f, w, v in parse_fields(block, 0, len(block)):
            if f == 1:  # stringtable
                for sf, sw, sv in parse_fields(block, v[0], v[1]):
                    if sf == 1: st.append(block[sv[0]:sv[1]].decode("utf-8", "replace"))
            elif f == 2: groups.append(v)
            elif f == 17: gran = v
            elif f == 19: lat_off = v
            elif f == 20: lon_off = v
        for gs, ge in groups:
            for f, w, v in parse_fields(block, gs, ge):
                if f == 2:  # dense
                    ids=lats=lons=None; kv=None
                    for df, dw, dv in parse_fields(block, v[0], v[1]):
                        if df == 1: ids = [zigzag(x) for x in packed_varints(block, dv[0], dv[1])]
                        elif df == 8: lats = [zigzag(x) for x in packed_varints(block, dv[0], dv[1])]
                        elif df == 9: lons = [zigzag(x) for x in packed_varints(block, dv[0], dv[1])]
                        elif df == 10: kv = packed_varints(block, dv[0], dv[1])
                    cid=clat=clon=0; kp=0
                    for i in range(len(ids)):
                        cid += ids[i]; clat += lats[i]; clon += lons[i]
                        latd = 1e-9 * (lat_off + gran * clat)
                        lond = 1e-9 * (lon_off + gran * clon)
                        node_coords[cid] = (latd, lond)
                        nodes += 1
                        for latd_,lond_ in [(latd,lond)]:
                            pass
                        lat_min = latd if lat_min is None else min(lat_min, latd)
                        lat_max = latd if lat_max is None else max(lat_max, latd)
                        lon_min = lond if lon_min is None else min(lon_min, lond)
                        lon_max = lond if lon_max is None else max(lon_max, lond)
                        # skip tags kv
                        if kv:
                            has=False
                            while kp < len(kv) and kv[kp] != 0:
                                has=True; kp += 2
                            kp += 1
                            if has and len(sample_nodes) < 3:
                                sample_nodes.append((cid, round(latd,6), round(lond,6)))
                elif f == 3:  # way
                    wid=0; refs=None; keys=None; vals=None
                    for wf, ww, wv in parse_fields(block, v[0], v[1]):
                        if wf == 1: wid = wv
                        elif wf == 2: keys = packed_varints(block, wv[0], wv[1])
                        elif wf == 3: vals = packed_varints(block, wv[0], wv[1])
                        elif wf == 8:
                            d = [zigzag(x) for x in packed_varints(block, wv[0], wv[1])]
                            acc=0; refs=[]
                            for x in d: acc+=x; refs.append(acc)
                    ways += 1
                    if refs and len(sample_ways) < 3:
                        tags = {}
                        if keys and vals:
                            tags = {st[keys[i]]: st[vals[i]] for i in range(min(len(keys),len(vals)))}
                        rc = node_coords.get(refs[0])
                        sample_ways.append((wid, len(refs), rc, tags.get("highway") or tags.get("building") or list(tags.keys())[:3]))
    print("nodes:", nodes, "ways:", ways)
    print("bbox lat:", round(lat_min,5), round(lat_max,5), "lon:", round(lon_min,5), round(lon_max,5))
    print("sample tagged nodes:", sample_nodes)
    print("sample ways (id, nrefs, first_node_coord, tag):")
    for s in sample_ways: print("   ", s)

if __name__ == "__main__":
    main(sys.argv[1])
