import { Parameter } from "metabase-types/types/Parameter";

import {
  doesOperatorExist,
  getOperatorByTypeAndName,
} from "metabase-lib/lib/operators/utils";
import { NUMBER, STRING, PRIMARY_KEY } from "metabase-lib/lib/types/constants";
import {
  getParameterType,
  getParameterSubType,
} from "metabase-lib/lib/parameters/utils/parameter-type";
import { PARAMETER_OPERATOR_TYPES } from "metabase-lib/lib/parameters/constants";

type OperatorType = "date" | "number" | "string";

export function getOperatorDisplayName(
  option: { type: string; name: string; operator: string },
  operatorType: OperatorType,
  sectionName?: string,
) {
  if (operatorType === "date" || operatorType === "number") {
    return option.name;
  } else if (operatorType === "string" && option.operator === "=") {
    return sectionName;
  } else {
    return `${sectionName} ${option.name.toLowerCase()}`;
  }
}

export function getParameterOperatorName(maybeOperatorName?: string) {
  return doesOperatorExist(maybeOperatorName) ? maybeOperatorName : "=";
}

export function deriveFieldOperatorFromParameter(parameter: Parameter) {
  const type = getParameterType(parameter);
  const subtype = getParameterSubType(parameter);
  const operatorType = getParameterOperatorType(type);
  const operatorName = getParameterOperatorName(subtype);

  return getOperatorByTypeAndName(operatorType, operatorName);
}

function getParameterOperatorType(parameterType?: string) {
  switch (parameterType) {
    case "number":
      return NUMBER;
    case "string":
    case "category":
    case "location":
      return STRING;
    case "id":
      // id can technically be a FK but doesn't matter as both use default filter operators
      return PRIMARY_KEY;
    default:
      return undefined;
  }
}

export function buildTypedOperatorOptions(
  operatorType: OperatorType,
  sectionId: string,
  sectionName: string,
) {
  return PARAMETER_OPERATOR_TYPES[operatorType].map(operatorOption => {
    return {
      ...operatorOption,
      sectionId,
      combinedName: getOperatorDisplayName(
        operatorOption,
        operatorType,
        sectionName,
      ),
    };
  });
}

export function getNumberParameterArity(parameter: Parameter) {
  switch (parameter.type) {
    case "number/=":
    case "number/!=":
      return "n";
    case "number/between":
      return 2;
    default:
      return 1;
  }
}

export function getStringParameterArity(parameter: Parameter) {
  switch (parameter.type) {
    case "string/=":
    case "string/!=":
      return "n";
    default:
      return 1;
  }
}
