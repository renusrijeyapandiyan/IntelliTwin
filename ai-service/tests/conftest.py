import pytest

from app import simpath  # noqa: F401
from training import train
from training.make_dataset import make_dataset


@pytest.fixture(scope="session")
def small_dataset():
    return make_dataset(seed=1, cycles=10)


@pytest.fixture(scope="session")
def trained_registry(tmp_path_factory, small_dataset):
    d = tmp_path_factory.mktemp("registry")
    ds = tmp_path_factory.mktemp("ds") / "d.csv"
    small_dataset.to_csv(ds, index=False)
    train.main(["--seed", "1", "--registry", str(d), "--dataset", str(ds)])
    return d
