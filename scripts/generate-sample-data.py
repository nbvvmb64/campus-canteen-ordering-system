"""Generate deterministic fictional CSV data for the public repository."""
from __future__ import annotations

import base64, csv, hashlib
from collections import Counter
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "examples" / "sample-data"
ANCHOR = datetime(2026, 9, 20)
STUDENT_PASSWORD = "DemoStudent!2026"
MERCHANT_PASSWORD = "DemoMerchant!2026"
MERCHANTS = (
    ("demo_merchant", "青禾餐厅"), ("south_kitchen", "南苑小厨"),
    ("grain_corner", "谷香档口"), ("light_bowl", "轻食工坊"),
    ("noodle_house", "拾味面馆"),
)
STUDENTS = (
    "demo_student", "campus_visitor", "library_reader", "lab_partner",
    "morning_runner", "music_club", "design_student", "coding_student",
    "sports_friend", "green_campus", "night_reader", "weekend_guest",
)
# Name, price, current stock, sale status. Dish names carry category meaning
# because the stable CSV contract intentionally has no category column.
DISHES = (
    (("招牌番茄牛肉饭", "22.00", 36, "ON_SALE"), ("香煎鸡肉能量碗", "24.00", 12, "ON_SALE"),
     ("青椒肉丝盖饭", "18.00", 7, "ON_SALE"), ("菌菇豆腐饭", "16.00", 0, "ON_SALE"),
     ("紫菜蛋花汤", "5.00", 42, "ON_SALE"), ("凉拌黄瓜", "8.00", 9, "ON_SALE"),
     ("清爽柠檬茶", "7.00", 28, "ON_SALE"), ("脆皮鸡块", "13.00", 18, "OFF_SALE")),
    (("菌菇鸡腿饭", "21.00", 25, "ON_SALE"), ("黑椒鸡排饭", "23.00", 11, "ON_SALE"),
     ("麻酱拌面", "17.00", 6, "ON_SALE"), ("鲜虾蔬菜卷", "19.00", 0, "ON_SALE"),
     ("鲜蔬豆腐汤", "8.00", 31, "ON_SALE"), ("桂花米糕", "6.00", 5, "ON_SALE"),
     ("乌梅饮", "7.00", 22, "ON_SALE"), ("香葱肉饼", "10.00", 14, "OFF_SALE")),
    (("土豆烧牛腩饭", "25.00", 33, "ON_SALE"), ("宫保鸡丁饭", "19.00", 13, "ON_SALE"),
     ("南瓜杂粮饭", "15.00", 8, "ON_SALE"), ("玉米鸡蛋炒饭", "14.00", 0, "ON_SALE"),
     ("冬瓜丸子汤", "9.00", 26, "ON_SALE"), ("蒜香西兰花", "7.00", 4, "ON_SALE"),
     ("红豆豆乳", "9.00", 19, "ON_SALE"), ("酱香卤蛋", "3.00", 30, "OFF_SALE")),
    (("照烧鸡胸轻食碗", "26.00", 29, "ON_SALE"), ("金枪鱼全麦三明治", "20.00", 10, "ON_SALE"),
     ("南瓜藜麦沙拉", "18.00", 6, "ON_SALE"), ("鲜果酸奶杯", "12.00", 0, "ON_SALE"),
     ("玉米浓汤", "9.00", 21, "ON_SALE"), ("烤时蔬拼盘", "11.00", 3, "ON_SALE"),
     ("无糖茉莉茶", "6.00", 38, "ON_SALE"), ("燕麦能量棒", "8.00", 16, "OFF_SALE")),
    (("红烧牛腩面", "24.00", 27, "ON_SALE"), ("番茄鸡蛋面", "16.00", 14, "ON_SALE"),
     ("麻辣拌面", "18.00", 7, "ON_SALE"), ("香菇鸡丝粥", "13.00", 0, "ON_SALE"),
     ("海带豆腐汤", "7.00", 24, "ON_SALE"), ("煎蛋", "3.00", 5, "ON_SALE"),
     ("冰柠檬水", "6.00", 35, "ON_SALE"), ("葱油花卷", "4.00", 20, "OFF_SALE")),
)

def password_fields(username: str, password: str) -> tuple[str, str]:
    salt = hashlib.sha256(f"public-demo:{username}".encode()).digest()[:16]
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 120_000, 32)
    return base64.b64encode(salt).decode(), base64.b64encode(digest).decode()

def write_csv(name: str, header: tuple[str, ...], rows: list[tuple[object, ...]]) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with (OUTPUT / name).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\r\n")
        writer.writerow(header); writer.writerows(rows)

def generate() -> dict[str, object]:
    users, merchant_ids, student_ids = [], [], []
    number = 1
    for index, (username, _) in enumerate(MERCHANTS):
        user_id = f"USR{number:06d}"; merchant_ids.append(user_id)
        salt, digest = password_fields(username, MERCHANT_PASSWORD)
        users.append((user_id, username, salt, digest, "MERCHANT", "ACTIVE", f"2026-01-{10 + index:02d} 08:30:00")); number += 1
    for index, username in enumerate(STUDENTS):
        user_id = f"USR{number:06d}"; student_ids.append(user_id)
        salt, digest = password_fields(username, STUDENT_PASSWORD)
        users.append((user_id, username, salt, digest, "STUDENT", "ACTIVE", f"2026-02-{1 + index:02d} 09:00:00")); number += 1

    dishes, dish_groups = [], []
    dish_number = 1
    for merchant_index, specs in enumerate(DISHES):
        group = []; merchant_id = merchant_ids[merchant_index]
        for local_index, (name, price, stock, status) in enumerate(specs):
            dish_id = f"DSH{dish_number:06d}"
            created = datetime(2026, 2, 15 + merchant_index, 8, 20) + timedelta(minutes=local_index * 3)
            updated = ANCHOR.replace(hour=8, minute=0) - timedelta(days=(local_index + merchant_index) % 5)
            dishes.append((dish_id, merchant_id, name, price, stock, status, created.strftime("%Y-%m-%d %H:%M:%S"), updated.strftime("%Y-%m-%d %H:%M:%S")))
            group.append({"id": dish_id, "name": name, "price": Decimal(price)})
            dish_number += 1
        dish_groups.append(group)

    orders, items, statuses = [], [], Counter()
    item_number = 1
    time_slots = ((11, 5), (11, 45), (12, 25), (17, 35))
    weighted = (0, 0, 0, 0, 1, 1, 1, 2, 2, 3, 4, 5, 6, 7)
    for order_index in range(180):
        days_ago = 44 - order_index // 4; slot = order_index % 4
        merchant_index = (order_index * 3 + days_ago) % len(MERCHANTS)
        student_id = student_ids[(order_index * 5 + days_ago) % len(student_ids)]
        merchant_id = merchant_ids[merchant_index]
        ordered_at = (ANCHOR - timedelta(days=days_ago)).replace(hour=time_slots[slot][0], minute=time_slots[slot][1], second=(order_index * 7) % 60)
        if days_ago >= 2: status = "CANCELLED" if order_index % 14 == 0 else "COMPLETED"
        else: status = ("PLACED", "PREPARING", "COMPLETED", "CANCELLED")[slot]
        statuses[status] += 1
        delay = {"PLACED": 0, "PREPARING": 12, "COMPLETED": 42 + order_index % 19, "CANCELLED": 4 + order_index % 8}[status]
        order_id = f"ORD{order_index + 1:06d}"; group = dish_groups[merchant_index]
        first_index = weighted[(order_index * 5 + days_ago) % len(weighted)]
        selected = [(group[first_index], 2 if order_index % 11 == 0 else 1)]
        if order_index % 5 != 0:
            second_index = weighted[(order_index * 7 + slot + 3) % len(weighted)]
            if second_index == first_index: second_index = (second_index + 1) % len(group)
            selected.append((group[second_index], 1))
        if order_index % 9 == 0:
            used = {dish["id"] for dish, _ in selected}; third_index = (order_index + 5) % len(group)
            while group[third_index]["id"] in used: third_index = (third_index + 1) % len(group)
            selected.append((group[third_index], 1))
        total = sum((dish["price"] * quantity for dish, quantity in selected), Decimal("0.00"))
        orders.append((order_id, student_id, merchant_id, ordered_at.strftime("%Y-%m-%d %H:%M:%S"), status, f"{total:.2f}", (ordered_at + timedelta(minutes=delay)).strftime("%Y-%m-%d %H:%M:%S")))
        for dish, quantity in selected:
            subtotal = dish["price"] * quantity
            items.append((f"ITM{item_number:06d}", order_id, dish["id"], dish["name"], quantity, f"{dish['price']:.2f}", f"{subtotal:.2f}")); item_number += 1

    write_csv("users.csv", ("user_id", "username", "password_salt", "password_hash", "role", "user_status", "created_at"), users)
    write_csv("dishes.csv", ("dish_id", "merchant_id", "dish_name", "unit_price", "stock", "sale_status", "created_at", "updated_at"), dishes)
    write_csv("orders.csv", ("order_id", "student_id", "merchant_id", "order_time", "order_status", "total_amount", "status_updated_at"), orders)
    write_csv("order_items.csv", ("order_item_id", "order_id", "dish_id", "dish_name", "quantity", "unit_price", "subtotal"), items)
    return {"users": len(users), "merchants": len(merchant_ids), "students": len(student_ids), "dishes": len(dishes), "orders": len(orders), "items": len(items), "statuses": dict(statuses)}

if __name__ == "__main__": print(generate())
