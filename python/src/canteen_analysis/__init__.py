"""校园餐厅订餐系统的 Python 数据分析模块。"""

from .analysis import analyze_dataset
from .errors import DataValidationError
from .loader import load_dataset
from .outputs import write_outputs

__all__ = [
    "DataValidationError",
    "analyze_dataset",
    "load_dataset",
    "write_outputs",
]
