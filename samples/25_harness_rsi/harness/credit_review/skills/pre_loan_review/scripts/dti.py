#!/usr/bin/env python3
"""确定性偿债收入比（DTI）计算器。

输入口径（由客户经理标准录入保证，脚本不做周期换算与合同去重）：
- --income         月收入（元），不要传年收入；
- --existing-debt  既有债务每月应还合计（元），同一合同只计一次；
- --new-payment    本次贷款每月应还金额（元）。

输出 JSON 字段：
- monthly_income             月收入（元）
- existing_monthly_debt      既有债务每月应还合计（元）
- new_monthly_payment        本次贷款每月应还金额（元）
- total_monthly_debt_service 合计月债务支出（元）
- debt_service_ratio         偿债收入比（百分比，保留两位小数）
- raw_ratio                  未舍入的偿债收入比（小数）
- above_50_percent           未舍入比值是否严格大于 50%
"""

from __future__ import annotations

import argparse
import json
import sys


def build_parser() -> argparse.ArgumentParser:
    """构建命令行参数解析器，帮助文本写明输入必须已月化、已去重。"""
    parser = argparse.ArgumentParser(
        prog="dti.py",
        description="计算偿债收入比＝（既有月债务＋本次月供）÷月收入，输出 JSON。",
        epilog=(
            "输入必须已月化、已去重：--income 传月收入（不要传年收入）；"
            "--existing-debt 传每月应还债务合计（同一合同只计一次）；"
            "--new-payment 传本次贷款每月应还金额。"
        ),
    )
    parser.add_argument(
        "--income",
        type=float,
        required=True,
        help="月收入（元）。必须是已折算的月口径，不得直接传年收入。",
    )
    parser.add_argument(
        "--existing-debt",
        type=float,
        required=True,
        help="既有债务每月应还合计（元）。必须已按合同去重，同一合同只计一次。",
    )
    parser.add_argument(
        "--new-payment",
        type=float,
        required=True,
        help="本次贷款每月应还金额（元）。",
    )
    return parser


def calculate(
    monthly_income: float,
    existing_monthly_debt: float,
    new_monthly_payment: float,
) -> dict:
    """按给定月化字段计算偿债收入比及判定结果。"""
    total_monthly_debt_service = existing_monthly_debt + new_monthly_payment
    raw_ratio = total_monthly_debt_service / monthly_income
    return {
        "monthly_income": monthly_income,
        "existing_monthly_debt": existing_monthly_debt,
        "new_monthly_payment": new_monthly_payment,
        "total_monthly_debt_service": total_monthly_debt_service,
        "debt_service_ratio": round(raw_ratio * 100, 2),
        "raw_ratio": raw_ratio,
        "above_50_percent": raw_ratio > 0.5,
    }


def main(argv: list[str] | None = None) -> int:
    """入口：校验输入、计算并打印 JSON 结果。"""
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.income <= 0:
        parser.error("--income 必须大于 0（月收入，元）；收到 %s" % args.income)
    if args.existing_debt < 0:
        parser.error("--existing-debt 不能为负数；收到 %s" % args.existing_debt)
    if args.new_payment < 0:
        parser.error("--new-payment 不能为负数；收到 %s" % args.new_payment)
    result = calculate(args.income, args.existing_debt, args.new_payment)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
