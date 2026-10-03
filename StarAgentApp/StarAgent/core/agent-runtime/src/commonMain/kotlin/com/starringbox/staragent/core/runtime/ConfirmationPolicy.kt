package com.starringbox.staragent.core.runtime

import com.starringbox.staragent.core.model.ExecutionPolicy

/** 与 UI 回答格式及执行运行时无关的授权语义。超时由宿主作为未获批准处理。 */
enum class ConfirmationDecision { Approved, Denied }

/** 工具声明本次操作的性质，核心权限规则决定是否必须取得明确批准。 */
fun requiresToolConfirmation(policy: ExecutionPolicy, required: Boolean, always: Boolean): Boolean =
    always || (policy == ExecutionPolicy.OnDemand && required)
