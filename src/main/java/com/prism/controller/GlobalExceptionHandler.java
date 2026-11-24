package com.prism.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tratamento global de erros dos endpoints REST.
 *
 * Todas as respostas de erro seguem o mesmo formato:
 *   {"status": "erro" | "ocupado", "mensagem": "..."}
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.prism.controller")
public class GlobalExceptionHandler {

    /** Falha de validação do corpo (@Valid), como IP vazio ou insumos ausentes. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validacao(MethodArgumentNotValidException e) {
        String mensagem = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("; "));
        return resposta(HttpStatus.BAD_REQUEST, "erro",
                mensagem.isBlank() ? "Dados inválidos" : mensagem);
    }

    /** JSON malformado ou tipo errado no corpo. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> corpoInvalido(HttpMessageNotReadableException e) {
        log.debug("Corpo da requisição não pôde ser lido: {}", e.getMessage());
        return resposta(HttpStatus.BAD_REQUEST, "erro", "Corpo da requisição inválido");
    }

    /** Parâmetro de URL ou query com tipo inválido, como uma data mal formatada. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> parametroInvalido(MethodArgumentTypeMismatchException e) {
        return resposta(HttpStatus.BAD_REQUEST, "erro", "Parâmetro inválido: " + e.getName());
    }

    /** Regras de negócio, como IP não cadastrado ou período com datas invertidas. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> requisicaoInvalida(IllegalArgumentException e) {
        return resposta(HttpStatus.BAD_REQUEST, "erro",
                Objects.toString(e.getMessage(), "Requisição inválida"));
    }

    /** Operação já em andamento, como uma coleta sendo executada. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> ocupado(IllegalStateException e) {
        return resposta(HttpStatus.CONFLICT, "ocupado",
                Objects.toString(e.getMessage(), "Operação em andamento"));
    }

    /** Qualquer outro erro não previsto. Registra o detalhe no log e devolve mensagem genérica. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> erroInterno(Exception e) {
        log.error("Erro não tratado na API", e);
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "erro",
                "Erro interno no servidor. Consulte o log.");
    }

    private static ResponseEntity<Map<String, String>> resposta(HttpStatus codigo, String status, String mensagem) {
        return ResponseEntity.status(codigo).body(Map.of("status", status, "mensagem", mensagem));
    }
}
