import argparse
import json
import os
import random
import shutil
from pathlib import Path

import numpy as np
import pandas as pd
import tensorflow as tf
from sklearn.metrics import accuracy_score, f1_score, precision_score, recall_score, roc_auc_score
from sklearn.preprocessing import RobustScaler
from tensorflow import keras
from tensorflow.keras import Model, layers


TARGET_COL = "status"
EMBEDDING_DIM = 32
BATCH_SIZE = 16
EPOCHS = 200
LEARNING_RATE = 0.0001
RANDOM_SEED = 75
L2_REG = 0.0004

ROBUST_COLS = [
    "length_url",
    "length_hostname",
    "nb_dots",
    "nb_hyphens",
    "nb_and",
    "nb_eq",
    "nb_underscore",
    "nb_percent",
    "nb_slash",
    "nb_colon",
    "nb_semicolumn",
    "nb_space",
    "nb_com",
    "ratio_digits_url",
    "ratio_digits_host",
    "length_words_raw",
    "char_repeat",
    "shortest_words_raw",
    "shortest_word_host",
    "shortest_word_path",
    "longest_words_raw",
    "longest_word_host",
    "longest_word_path",
    "avg_words_raw",
    "avg_word_host",
    "avg_word_path",
    "phish_hints",
    "nb_extCSS",
]


def parse_args():
    parser = argparse.ArgumentParser(description="Retrain the 54-feature static phishing TFLite model.")
    parser.add_argument(
        "--repo-root",
        default=os.environ.get("QR_PHISHING_REPO", str(Path(__file__).resolve().parents[1])),
        help="Repository root. Defaults to the parent of this script directory.",
    )
    parser.add_argument("--deploy", action="store_true", help="Copy generated artifacts into app/src/main/assets.")
    return parser.parse_args()


def set_seed(seed):
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)


def split_class(class_df, train_ratio=0.6, val_ratio=0.2, seed=42):
    shuffled = class_df.sample(frac=1, random_state=seed)
    shuffled = shuffled.sample(frac=1, random_state=seed + 1)
    shuffled = shuffled.sample(frac=1, random_state=seed + 2)

    n_rows = len(shuffled)
    train_end = int(n_rows * train_ratio)
    val_end = train_end + int(n_rows * val_ratio)
    return shuffled.iloc[:train_end], shuffled.iloc[train_end:val_end], shuffled.iloc[val_end:]


def load_split_data(data_path):
    df = pd.read_csv(data_path)
    df_work = df.copy()
    df_work[TARGET_COL] = df_work[TARGET_COL].astype(int)

    feature_names = [col for col in df_work.columns if col != TARGET_COL]
    if len(feature_names) != 54:
        raise ValueError(f"Expected 54 features, got {len(feature_names)}")

    missing_robust = [col for col in ROBUST_COLS if col not in feature_names]
    if missing_robust:
        raise ValueError(f"Robust columns missing from dataset: {missing_robust}")

    df_legit = df_work[df_work[TARGET_COL] == 0]
    df_phish = df_work[df_work[TARGET_COL] == 1]

    legit_train, legit_val, legit_test = split_class(df_legit, seed=RANDOM_SEED)
    phish_train, phish_val, phish_test = split_class(df_phish, seed=RANDOM_SEED)

    train_df = pd.concat([legit_train, phish_train]).sample(frac=1, random_state=RANDOM_SEED)
    val_df = pd.concat([legit_val, phish_val]).sample(frac=1, random_state=RANDOM_SEED)
    test_df = pd.concat([legit_test, phish_test]).sample(frac=1, random_state=RANDOM_SEED)

    x_train = train_df.drop(columns=[TARGET_COL]).values.astype(np.float32)
    y_train = train_df[TARGET_COL].values.astype(np.float32)
    x_val = val_df.drop(columns=[TARGET_COL]).values.astype(np.float32)
    y_val = val_df[TARGET_COL].values.astype(np.float32)
    x_test = test_df.drop(columns=[TARGET_COL]).values.astype(np.float32)
    y_test = test_df[TARGET_COL].values.astype(np.float32)

    return feature_names, (x_train, y_train), (x_val, y_val), (x_test, y_test)


def scale_data(feature_names, train, val, test):
    x_train, y_train = train
    x_val, y_val = val
    x_test, y_test = test

    robust_indices = [feature_names.index(col) for col in ROBUST_COLS]
    raw_indices = [idx for idx in range(len(feature_names)) if idx not in robust_indices]

    scaler = RobustScaler()
    x_train_scaled = x_train.copy().astype(np.float32)
    x_val_scaled = x_val.copy().astype(np.float32)
    x_test_scaled = x_test.copy().astype(np.float32)

    x_train_scaled[:, robust_indices] = scaler.fit_transform(x_train[:, robust_indices]).astype(np.float32)
    x_val_scaled[:, robust_indices] = scaler.transform(x_val[:, robust_indices]).astype(np.float32)
    x_test_scaled[:, robust_indices] = scaler.transform(x_test[:, robust_indices]).astype(np.float32)

    scaler_params = {
        "type": "robust_only",
        "robust_cols": ROBUST_COLS,
        "robust_center": scaler.center_.tolist(),
        "robust_scale": scaler.scale_.tolist(),
        "raw_cols": [feature_names[idx] for idx in raw_indices],
    }

    return (
        (x_train_scaled, y_train),
        (x_val_scaled, y_val),
        (x_test_scaled, y_test),
        scaler_params,
    )


def build_classifier(input_dim):
    keras.backend.clear_session()

    encoder_input = keras.Input(shape=(input_dim,), name="encoder_input")
    x = layers.Dense(
        256,
        activation="relu",
        kernel_initializer="he_normal",
        kernel_regularizer=keras.regularizers.l2(L2_REG),
        name="encoder_dense1",
    )(encoder_input)
    x = layers.Dense(
        128,
        activation="relu",
        kernel_initializer="he_normal",
        kernel_regularizer=keras.regularizers.l2(L2_REG),
        name="encoder_dense2",
    )(x)
    x = layers.Dense(
        64,
        activation="relu",
        kernel_initializer="he_normal",
        kernel_regularizer=keras.regularizers.l2(L2_REG),
        name="encoder_dense3",
    )(x)
    embedding = layers.Dense(
        EMBEDDING_DIM,
        activation="relu",
        kernel_regularizer=keras.regularizers.l2(L2_REG),
        name="embedding",
    )(x)
    x = layers.Dense(
        32,
        activation="relu",
        kernel_initializer="he_normal",
        kernel_regularizer=keras.regularizers.l2(L2_REG),
        name="classifier_dense1",
    )(embedding)
    x = layers.Dense(
        16,
        activation="relu",
        kernel_regularizer=keras.regularizers.l2(L2_REG),
        name="classifier_dense2",
    )(x)
    output = layers.Dense(1, activation="sigmoid", name="output")(x)

    classifier = Model(inputs=encoder_input, outputs=output, name="phishing_classifier")
    classifier.compile(
        optimizer=keras.optimizers.Adam(learning_rate=LEARNING_RATE),
        loss="binary_crossentropy",
        metrics=["accuracy", keras.metrics.AUC(name="auc")],
    )
    return classifier


def evaluate_predictions(y_true, proba, threshold=0.5):
    binary = (proba > threshold).astype(int)
    return {
        "accuracy": float(accuracy_score(y_true, binary)),
        "precision": float(precision_score(y_true, binary, zero_division=0)),
        "recall": float(recall_score(y_true, binary, zero_division=0)),
        "f1": float(f1_score(y_true, binary, zero_division=0)),
        "auc": float(roc_auc_score(y_true, proba)),
    }


def convert_to_tflite(classifier, tflite_path):
    converter = tf.lite.TFLiteConverter.from_keras_model(classifier)
    tflite_model = converter.convert()
    tflite_path.write_bytes(tflite_model)


def predict_tflite(tflite_path, x_values):
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    predictions = []
    for row in x_values.astype(np.float32):
        sample = row.reshape(1, -1)
        interpreter.set_tensor(input_details[0]["index"], sample)
        interpreter.invoke()
        predictions.append(float(interpreter.get_tensor(output_details[0]["index"])[0][0]))
    return np.array(predictions, dtype=np.float32)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def main():
    args = parse_args()
    repo_root = Path(args.repo_root)
    data_path = repo_root / "phishing" / "phishing_data_tflite_ready.csv"
    output_dir = repo_root / "phishing" / "retrained_static_54"
    assets_dir = repo_root / "app" / "src" / "main" / "assets"
    output_dir.mkdir(parents=True, exist_ok=True)

    set_seed(RANDOM_SEED)

    print(f"TensorFlow: {tf.__version__}")
    print(f"Data: {data_path}")
    feature_names, train, val, test = load_split_data(data_path)
    train_scaled, val_scaled, test_scaled, scaler_params = scale_data(feature_names, train, val, test)
    x_train, y_train = train_scaled
    x_val, y_val = val_scaled
    x_test, y_test = test_scaled

    print(f"Features: {len(feature_names)}")
    print(f"Train: {x_train.shape}, legit={(y_train == 0).sum()}, phishing={(y_train == 1).sum()}")
    print(f"Val:   {x_val.shape}, legit={(y_val == 0).sum()}, phishing={(y_val == 1).sum()}")
    print(f"Test:  {x_test.shape}, legit={(y_test == 0).sum()}, phishing={(y_test == 1).sum()}")

    classifier = build_classifier(x_train.shape[1])
    callbacks = [
        keras.callbacks.EarlyStopping(monitor="val_loss", patience=20, restore_best_weights=True, verbose=1),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=7, min_lr=1e-6, verbose=1),
    ]

    history = classifier.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        callbacks=callbacks,
        verbose=2,
    )

    val_proba = classifier.predict(x_val, verbose=0).flatten()
    test_proba = classifier.predict(x_test, verbose=0).flatten()
    metrics = {
        "seed": RANDOM_SEED,
        "epochs_run": len(history.history["loss"]),
        "threshold": 0.5,
        "val": evaluate_predictions(y_val, val_proba),
        "test": evaluate_predictions(y_test, test_proba),
    }

    keras_path = output_dir / "classifier_model.keras"
    tflite_path = output_dir / "phishing_classifier.tflite"
    scaler_path = output_dir / "scaler_params.json"
    feature_info_path = output_dir / "feature_info.json"
    metrics_path = output_dir / "metrics.json"

    classifier.save(keras_path)
    write_json(scaler_path, scaler_params)
    write_json(
        feature_info_path,
        {
            "feature_columns": feature_names,
            "input_shape": [len(feature_names)],
            "normalization_layer": "norm_all",
            "labeling": "both",
            "uncertainty_notify_eps": 0.05,
        },
    )

    convert_to_tflite(classifier, tflite_path)
    tflite_proba = predict_tflite(tflite_path, x_test)
    metrics["tflite_test"] = evaluate_predictions(y_test, tflite_proba)
    metrics["keras_tflite_mae"] = float(np.mean(np.abs(test_proba - tflite_proba)))
    metrics["keras_tflite_max_abs_error"] = float(np.max(np.abs(test_proba - tflite_proba)))
    write_json(metrics_path, metrics)

    print("Metrics:")
    print(json.dumps(metrics, indent=2))
    print(f"Saved output: {output_dir}")

    if args.deploy:
        assets_dir.mkdir(parents=True, exist_ok=True)
        for src, name in [
            (tflite_path, "phishing_classifier.tflite"),
            (scaler_path, "scaler_params.json"),
            (feature_info_path, "feature_info.json"),
        ]:
            shutil.copy2(src, assets_dir / name)
            print(f"Deployed: {assets_dir / name}")


if __name__ == "__main__":
    main()
