"""
embedding_model.ipynb과 xgboost의 분할 로직 검증
"""
import numpy as np
import pandas as pd

# 샘플 데이터 생성
np.random.seed(42)
n_legit = 5000
n_phish = 5000
total = n_legit + n_phish

df = pd.DataFrame({
    'feature1': np.random.randn(total),
    'feature2': np.random.randn(total),
    'status': np.concatenate([np.zeros(n_legit), np.ones(n_phish)])
})

print("=" * 70)
print("원본 데이터 분포")
print("=" * 70)
print(f"전체: {len(df)}")
print(f"정상(0): {(df['status']==0).sum()}")
print(f"피싱(1): {(df['status']==1).sum()}")

# ===== 현재 embedding_model 방식 =====
print("\n" + "=" * 70)
print("embedding_model.ipynb 방식 (각 클래스별 60:20:20 분할)")
print("=" * 70)

RANDOM_SEED = 42
TARGET_COL = 'status'

def split_class(class_df, train_ratio=0.6, val_ratio=0.2, seed=42):
    """클래스별로 train/val/test 분할"""
    shuffled = class_df.sample(frac=1, random_state=seed)
    shuffled = shuffled.sample(frac=1, random_state=seed+1)
    shuffled = shuffled.sample(frac=1, random_state=seed+2)
    
    n = len(shuffled)
    train_end = int(n * train_ratio)
    val_end = int(n * (train_ratio + val_ratio))
    
    return (shuffled.iloc[:train_end], 
            shuffled.iloc[train_end:val_end], 
            shuffled.iloc[val_end:])

df_work = df.copy()

# 클래스별 분리
df_legit = df_work[df_work[TARGET_COL] == 0]
df_phish = df_work[df_work[TARGET_COL] == 1]

print(f"\n정상(0) 데이터: {len(df_legit)}")
print(f"피싱(1) 데이터: {len(df_phish)}")

# 각 클래스별 60:20:20 분할
legit_train, legit_val, legit_test = split_class(df_legit, seed=RANDOM_SEED)
phish_train, phish_val, phish_test = split_class(df_phish, seed=RANDOM_SEED)

print(f"\n정상 클래스 분할:")
print(f"  Train: {len(legit_train)} ({len(legit_train)/len(df_legit)*100:.1f}%)")
print(f"  Val:   {len(legit_val)} ({len(legit_val)/len(df_legit)*100:.1f}%)")
print(f"  Test:  {len(legit_test)} ({len(legit_test)/len(df_legit)*100:.1f}%)")

print(f"\n피싱 클래스 분할:")
print(f"  Train: {len(phish_train)} ({len(phish_train)/len(df_phish)*100:.1f}%)")
print(f"  Val:   {len(phish_val)} ({len(phish_val)/len(df_phish)*100:.1f}%)")
print(f"  Test:  {len(phish_test)} ({len(phish_test)/len(df_phish)*100:.1f}%)")

# 합치기
train_df = pd.concat([legit_train, phish_train]).sample(frac=1, random_state=RANDOM_SEED)
val_df = pd.concat([legit_val, phish_val]).sample(frac=1, random_state=RANDOM_SEED)
test_df = pd.concat([legit_test, phish_test]).sample(frac=1, random_state=RANDOM_SEED)

y_train = train_df[TARGET_COL].values
y_val = val_df[TARGET_COL].values
y_test = test_df[TARGET_COL].values

print(f"\n최종 분할 (합친 후):")
print(f"Train: {len(train_df)} - 정상:{(y_train==0).sum()}, 피싱:{(y_train==1).sum()}")
print(f"Val:   {len(val_df)} - 정상:{(y_val==0).sum()}, 피싱:{(y_val==1).sum()}")
print(f"Test:  {len(test_df)} - 정상:{(y_test==0).sum()}, 피싱:{(y_test==1).sum()}")

print(f"\n각 세트의 피싱 비율:")
print(f"Train 피싱 비율: {y_train.mean()*100:.1f}%")
print(f"Val 피싱 비율:   {y_val.mean()*100:.1f}%")
print(f"Test 피싱 비율:  {y_test.mean()*100:.1f}%")

# ===== 문제점 확인 =====
print("\n" + "=" * 70)
print("문제점 분석")
print("=" * 70)

# 1) 데이터 중복 확인
total_samples = len(train_df) + len(val_df) + len(test_df)
print(f"\n1️⃣ 데이터 중복 확인:")
print(f"   Train + Val + Test = {total_samples}")
print(f"   원본 데이터 = {len(df)}")
print(f"   ✅ 일치" if total_samples == len(df) else f"   ❌ 불일치 (손실: {len(df) - total_samples})")

# 2) 비율 확인
expected_train = int(len(df) * 0.6)
expected_val = int(len(df) * 0.2)
expected_test = len(df) - expected_train - expected_val

print(f"\n2️⃣ 예상 비율 확인 (60:20:20):")
print(f"   예상 Train: {expected_train} ({expected_train/len(df)*100:.1f}%)")
print(f"   예상 Val:   {expected_val} ({expected_val/len(df)*100:.1f}%)")
print(f"   예상 Test:  {expected_test} ({expected_test/len(df)*100:.1f}%)")
print(f"\n   실제 Train: {len(train_df)} ({len(train_df)/len(df)*100:.1f}%)")
print(f"   실제 Val:   {len(val_df)} ({len(val_df)/len(df)*100:.1f}%)")
print(f"   실제 Test:  {len(test_df)} ({len(test_df)/len(df)*100:.1f}%)")

# 3) 50:50 균형 확인
print(f"\n3️⃣ 각 세트에서 50:50 균형 확인:")
for name, y in [('Train', y_train), ('Val', y_val), ('Test', y_test)]:
    legit_ratio = (y==0).sum() / len(y) * 100
    phish_ratio = (y==1).sum() / len(y) * 100
    print(f"   {name}: 정상 {legit_ratio:.1f}%, 피싱 {phish_ratio:.1f}%")

print("\n" + "=" * 70)
