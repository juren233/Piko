#include "transport/xqc_conn.h"
#include "transport/xqc_stream.h"

void *piko_xqc_stream_proto_data(xqc_stream_t *stream) {
    if (stream == 0 || stream->stream_conn == 0) {
        return 0;
    }
    return stream->stream_conn->proto_data;
}
