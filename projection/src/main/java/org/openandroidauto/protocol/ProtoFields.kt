package org.openandroidauto.protocol

/** Bounded protobuf wire reader for the small protocol messages used by this client. */
internal object ProtoFields {
    data class Field(val number: Int, val integer: Long? = null, val bytes: ByteArray? = null)
    fun read(bytes: ByteArray): List<Field> {
        require(bytes.size <= MessageFramer.MAX_MESSAGE_PAYLOAD)
        var position=0
        fun variable():Long { var value=0L;var shift=0
            while(position<bytes.size&&shift<70){val n=bytes[position++].toInt() and 255
                if(shift==63)require(n and 254==0){"Varint overflow"}
                value=value or((n and 127).toLong() shl shift)
                if(n and 128==0)return value
                shift+=7
            };error("Truncated varint")
        }
        val result=mutableListOf<Field>()
        while(position<bytes.size){require(result.size<4096){"Excessive protobuf fields"};val tag=variable();val field=(tag ushr 3).toInt();require(field>0)
            when((tag and 7).toInt()){
                0->result.add(Field(field,integer=variable()))
                1->{require(bytes.size-position>=8);position+=8}
                2->{val length=variable();require(length>=0&&length<=bytes.size-position);val end=position+length.toInt();result.add(Field(field,bytes=bytes.copyOfRange(position,end)));position=end}
                5->{require(bytes.size-position>=4);position+=4}
                else->error("Unsupported protobuf wire type")
            }
        };return result
    }
    fun integer(fields:List<Field>,number:Int,default:Int=0)=fields.firstOrNull{it.number==number}?.integer?.toInt()?:default
    fun repeatedUnsignedIntegers(fields: List<Field>, number: Int): List<Int> {
        val values = mutableListOf<Int>()
        for (field in fields.filter { it.number == number }) {
            field.integer?.let { require(it in 0..Int.MAX_VALUE.toLong()); values.add(it.toInt()) }
            val packed = field.bytes ?: continue
            var position = 0
            while (position < packed.size) {
                require(values.size < 4096) { "Excessive repeated integers" }
                var value = 0L
                var shift = 0
                var complete = false
                while (position < packed.size && shift < 35) {
                    val next = packed[position++].toInt() and 255
                    value = value or ((next and 127).toLong() shl shift)
                    if (next and 128 == 0) { complete = true; break }
                    shift += 7
                }
                require(complete && value <= Int.MAX_VALUE) { "Invalid packed integer" }
                values.add(value.toInt())
            }
        }
        return values
    }
    fun nested(fields:List<Field>,number:Int)=fields.filter{it.number==number&&it.bytes!=null}.map{read(it.bytes!!)}
}
