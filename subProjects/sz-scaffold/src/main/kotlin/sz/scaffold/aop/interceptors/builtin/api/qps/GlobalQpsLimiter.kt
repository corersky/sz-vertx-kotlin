package sz.scaffold.aop.interceptors.builtin.api.qps

import sz.scaffold.aop.interceptors.GlobalInterceptorBase
import sz.scaffold.controller.reply.ReplyBase
import sz.scaffold.errors.builtin.SzErrors

// app.httpServer.interceptors 中的配置
// includes 和 excludes 采用通配符匹配, excludes 比 includes 的优先级高
// Possible patterns allow to match single characters ('?') or any count of characters ('*').
// Wildcard characters can be escaped (by an '\'). When matching path, deep tree wildcard also can be used ('**').
// app {
//     httpServer {
//       interceptors = [
//         {
//             className = "sz.scaffold.aop.interceptors.builtin.api.qps.GlobalQpsLimiter"
//             config = {
//             name = "nameOfLimiter"
//             qps = 150
//                 includes = ["/**"]
//                 excludes = []
//             }
//         }
//       ]
//     }
//   }


@Suppress("UnstableApiUsage")
class GlobalQpsLimiter : GlobalInterceptorBase() {

    private val limiterName = config.getString("name")

    override suspend fun whenMatch(): Any? {
        val limiter = QpsLimiterMap.namedLimiterOf(limiterName)
        return if (limiter.tryAcquire()) {
            delegate.call()
        } else {
            val reply = ReplyBase()
            reply.ret = SzErrors.ExceedQpsLimit.code
            reply.errmsg = "${SzErrors.ExceedQpsLimit.desc}: [max ${limiter.rate} times/seconds]"
            reply
        }
    }
}